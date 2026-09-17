/**
 * Native root daemon for guru.
 *
 * This is a minimal C program that:
 * 1. Drops a shell that runs as root (UID 0)
 * 2. Reads commands from a Unix socket
 * 3. Executes them via /system/bin/sh
 * 4. Returns stdout/stderr/exit code
 *
 * Launched via: su -c /data/data/com.unuslumen.app.guru/files/rootshell
 * Or if already root: /data/data/com.unuslumen.app.guru/files/rootshell
 *
 * Protocol (binary, over Unix socket):
 *   [4 bytes LE: command length][command bytes]
 *   Response:
 *   [4 bytes LE: exit code][4 bytes LE: stdout length][stdout][4 bytes LE: stderr length][stderr]
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <errno.h>
#include <signal.h>

#define SOCKET_PATH "/data/local/tmp/guru_rootshell"
#define MAX_CMD_LEN 65536
#define READ_BUF_SIZE 4096

static int read_exact(int fd, void *buf, size_t len) {
    size_t total = 0;
    while (total < len) {
        ssize_t n = read(fd, (char *)buf + total, len - total);
        if (n <= 0) return -1;
        total += n;
    }
    return 0;
}

static int write_exact(int fd, const void *buf, size_t len) {
    size_t total = 0;
    while (total < len) {
        ssize_t n = write(fd, (const char *)buf + total, len - total);
        if (n <= 0) return -1;
        total += n;
    }
    return 0;
}

static void write_int32(int fd, int32_t val) {
    uint8_t buf[4];
    buf[0] = val & 0xFF;
    buf[1] = (val >> 8) & 0xFF;
    buf[2] = (val >> 16) & 0xFF;
    buf[3] = (val >> 24) & 0xFF;
    write_exact(fd, buf, 4);
}

static int32_t read_int32(int fd) {
    uint8_t buf[4];
    if (read_exact(fd, buf, 4) < 0) return -1;
    return buf[0] | (buf[1] << 8) | (buf[2] << 16) | (buf[3] << 24);
}

static char *read_string(int fd, int32_t *out_len) {
    int32_t len = read_int32(fd);
    if (len < 0 || len > MAX_CMD_LEN) return NULL;
    char *str = malloc(len + 1);
    if (!str) return NULL;
    if (read_exact(fd, str, len) < 0) {
        free(str);
        return NULL;
    }
    str[len] = '\0';
    if (out_len) *out_len = len;
    return str;
}

static void write_string(int fd, const char *str, int32_t len) {
    write_int32(fd, len);
    if (len > 0) write_exact(fd, str, len);
}

static char *read_all_fd(int fd, int32_t *out_len) {
    char *buf = NULL;
    size_t cap = 0;
    size_t len = 0;
    char tmp[READ_BUF_SIZE];

    while (1) {
        ssize_t n = read(fd, tmp, sizeof(tmp));
        if (n <= 0) break;
        if (len + n > cap) {
            cap = cap ? cap * 2 : READ_BUF_SIZE;
            buf = realloc(buf, cap);
        }
        memcpy(buf + len, tmp, n);
        len += n;
    }

    if (out_len) *out_len = (int32_t)len;
    if (!buf) buf = strdup("");
    return buf;
}

static int execute_command(const char *command, char **out_stdout, int32_t *out_stdout_len,
                           char **out_stderr, int32_t *out_stderr_len) {
    int stdout_pipe[2], stderr_pipe[2];

    if (pipe(stdout_pipe) < 0 || pipe(stderr_pipe) < 0) {
        return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        close(stderr_pipe[0]); close(stderr_pipe[1]);
        return -1;
    }

    if (pid == 0) {
        // Child
        dup2(stdout_pipe[1], STDOUT_FILENO);
        dup2(stderr_pipe[1], STDERR_FILENO);
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        close(stderr_pipe[0]); close(stderr_pipe[1]);

        // Close all other FDs
        for (int i = 3; i < 256; i++) close(i);

        setsid();
        execl("/system/bin/sh", "sh", "-c", command, (char *)NULL);
        _exit(127);
    }

    // Parent
    close(stdout_pipe[1]);
    close(stderr_pipe[1]);

    *out_stdout = read_all_fd(stdout_pipe[0], out_stdout_len);
    *out_stderr = read_all_fd(stderr_pipe[0], out_stderr_len);

    close(stdout_pipe[0]);
    close(stderr_pipe[0]);

    int status;
    waitpid(pid, &status, 0);

    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1;
}

static void handle_client(int client_fd) {
    while (1) {
        int32_t cmd_len;
        char *command = read_string(client_fd, &cmd_len);
        if (!command) break;

        if (cmd_len == 0) {
            free(command);
            continue;
        }

        char *stdout_str = NULL, *stderr_str = NULL;
        int32_t stdout_len = 0, stderr_len = 0;

        int exit_code = execute_command(command, &stdout_str, &stdout_len,
                                        &stderr_str, &stderr_len);

        write_int32(client_fd, exit_code);
        write_string(client_fd, stdout_str ? stdout_str : "", stdout_len);
        write_string(client_fd, stderr_str ? stderr_str : "", stderr_len);

        free(command);
        free(stdout_str);
        free(stderr_str);
    }
    close(client_fd);
}

int main(int argc, char **argv) {
    // Ensure we're root
    if (getuid() != 0) {
        fprintf(stderr, "rootshell: must run as root (current uid=%d)\n", getuid());
        return 1;
    }

    // Daemonize
    if (fork() > 0) {
        // Parent exits, daemon runs in background
        return 0;
    }

    setsid();
    signal(SIGHUP, SIG_IGN);

    // Clean up old socket
    unlink(SOCKET_PATH);

    // Create Unix socket
    int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) {
        perror("rootshell: socket");
        return 1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path) - 1);

    if (bind(server_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("rootshell: bind");
        close(server_fd);
        return 1;
    }

    // Make socket world-accessible
    chmod(SOCKET_PATH, 0666);

    if (listen(server_fd, 5) < 0) {
        perror("rootshell: listen");
        close(server_fd);
        return 1;
    }

    // Accept connections
    while (1) {
        int client_fd = accept(server_fd, NULL, NULL);
        if (client_fd < 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (fork() == 0) {
            close(server_fd);
            handle_client(client_fd);
            exit(0);
        }
        close(client_fd);

        // Reap zombies
        while (waitpid(-1, NULL, WNOHANG) > 0);
    }

    close(server_fd);
    unlink(SOCKET_PATH);
    return 0;
}
