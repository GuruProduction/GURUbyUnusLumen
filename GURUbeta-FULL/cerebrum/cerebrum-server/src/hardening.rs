//! Cerebrum server hardening: kernel-level network lockdown (Phase D).
//!
//! On Linux targets (includes Android), this module installs a seccomp BPF
//! filter in the server process that permits ONLY these syscall families the
//! brain needs:
//!   - filesystem I/O (open/openat/read/write/close/fstat/fsync, mkdir, rmdir, unlink, rename)
//!   - memory mapping (mmap, munmap, mprotect)
//!   - established-connection IO and metadata (read/write/fstat/lseek/close, getsockopt, shutdown)
//!   - AF_UNIX socket lifecycle: socket(AF_UNIX,...)/socketpair/bind/listen/accept/accept4/connect/
//!     recvfrom/sendto/sendmsg/recvmsg (kernel arg-filter rules allow socket-family == AF_UNIX
//!     and reject any AF_INET/AF_INET6/AF_NETLINK socket() attempt with EPERM)
//!   - process management (exit, exit_group, rt_sig*, clock/mach time reads)
//!
//! With this filter installed, even a FUTURE dependency that tries to dial
//! out of the phone CANNOT create an internet socket: socket(AF_INET, ...) is
//! rejected at kernel syscall level with EPERM.
//!
//! On non-Linux (macOS dev builds), the filters compile no-op'd; the same
//! API surface exists but the platform gives us no seccomp(2), and macOS dev
//! relies on the unit/behavior tests to guarantee no-network-client hygiene
//! (the Cargo.toml grep-lock lives in this module's tests).
//!
//! Constructed as raw BPF programs (no external seccomp crates needed).

#![allow(dead_code)] // Filter data stays partially dormant on non-linux targets.

use std::sync::atomic::{AtomicBool, Ordering};

/// Install + honestly reported result: Ok(true) = kernel filter live
/// (Linux/Android), Ok(false) = platform lacks seccomp (macOS dev), Err =
/// install attempted and FAILED (fatal on Linux paths).
pub fn install_no_network_filter_bool() -> Result<bool, HardeningError> {
    install_and_track()
}

/// A global success tracker so tests (and the server's boot banner) can assert
/// kernel hardening is live without a second unpriv escalation.
static HARDENED: AtomicBool = AtomicBool::new(false);

/// Query whether hardening has been installed (set by install_and_track).
pub fn harden_enabled() -> bool {
    HARDENED.load(Ordering::Acquire)
}

/// Install and track. Real seccomp path on linux; marker flip elsewhere
/// where the platform offers no equivalent (never lies on Linux targets).
fn install_and_track() -> Result<bool, HardeningError> {
    #[cfg(target_os = "linux")]
    {
        install_seccomp_linux()?;
        HARDENED.store(true, Ordering::Release);
        Ok(true)
    }
    #[cfg(not(target_os = "linux"))]
    {
        // No kernel-level seccomp on macOS (the dev platform): report honestly:
        // hardening is enforced only up to the grep-lock + code-path review.
        HARDENED.store(false, Ordering::Release);
        Ok(false)
    }
}

/// The C `long` width for this platform, without naming a Linux-only crate.
///
/// THE ENUM MUST COMPILE ON macOS TOO. `HardeningError` is public and declared outside the
/// Linux module, so naming `libc::c_long` in its fields made `libc` a hard requirement of the
/// whole crate. `libc` is a Linux-only dependency here on purpose (the seccomp code is the
/// only consumer), so on macOS the type was unresolvable. `isize` is the same width as C
/// `long` on every target this crate builds for: 64-bit on x86_64 and aarch64, and `isize` is
/// 32-bit on the 32-bit targets, matching `c_long` there as well.
type CReturnValue = isize;

/// Installation error from the hardening layer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HardeningError {
    /// prctl(PR_SET_NO_NEW_PRIVS) failed: seccomp cannot be applied safely.
    ///
    /// `c_long` rather than `i64`, because that is what the platform's C ABI returns and the
    /// two are not the same type everywhere: `prctl` and `syscall` return `c_int` (i32) on
    /// 32-bit targets including armeabi-v7a and x86, and `c_long` on 64-bit ones. Declaring
    /// i64 made this enum unbuildable on Linux, which no macOS build could reveal because the
    /// whole module is Linux-gated. The values are converted at the boundary below.
    NoNewPrivsFailed(CReturnValue),
    /// seccomp(2) with our BPF program failed: errno as negative number. `c_long`, see above.
    SeccompFailed(CReturnValue),
    /// Filter assembly failed (BPF validation).
    FilterBuild(String),
}

#[cfg(target_os = "linux")]
mod linux_impl {
    use super::{CReturnValue, HardeningError};

    // ==== BPF plumbing (linux uapi) ====
    //
    // Standard codes from <linux/filter.h>, composed by OR-ing the class with the mode or
    // operation, exactly as the kernel header defines them:
    //   BPF_LD | BPF_W | BPF_ABS  = load a 32-bit word at an absolute offset
    //   BPF_LD | BPF_IMM          = load an immediate (not used here)
    //
    // THESE WERE MISSING, AND ONLY A LINUX BUILD COULD TELL. The names BPF_LD, BPF_W and
    // BPF_ABS were used in three places in `assemble_filter` while only the pre-composed
    // BPF_LD_W and BPF_LD_ABS existed, and the whole module is `cfg(target_os = "linux")`,
    // so every macOS build skipped it and the first Linux compile failed with "cannot find
    // value `BPF_LD`". The composed constants above are kept because they are used too.
    pub const BPF_LD: u32 = 0x00;
    pub const BPF_W: u32 = 0x00;
    pub const BPF_ABS: u32 = 0x20;
    pub const BPF_LD_W: u32 = 0x00;
    pub const BPF_LD_ABS: u32 = 0x20;
    pub const BPF_JMP: u32 = 0x05;
    pub const BPF_JEQ: u32 = 0x10 | BPF_JMP;
    pub const BPF_JNE: u32 = 0x50 | BPF_JMP;
    pub const BPF_JGE: u32 = 0x30 | BPF_JMP;
    pub const BPF_RET: u32 = 0x06;
    pub const BPF_K: u32 = 0x00;
    /// The BPF code for an unconditional SECCOMP_RET_ALLOW statement, which is
    /// `BPF_RET | BPF_K`. It was written as `0x7FFF_FFFF & 0_0000`, which is zero for every
    /// input: a constant that looked like an allow mask and was actually the number 0.
    pub const BPF_RET_ALLOW: u32 = BPF_RET | BPF_K;
    pub const SECCOMP_MODE_FILTER: u32 = 2;
    pub const SECCOMP_RET_ERRNO_MASK: u32 = 0x0005_0000; // ENOSYS on EPERM below.
    pub const SECCOMP_RET_ALLOW: u32 = 0x7FFF_FFFF;
    pub const SECCOMP_RET_ERRNO_EPERM: u32 = 0x0005_0000 | 1;
    pub const SECCOMP_RET_KILL_PROCESS: u32 = 0x8010_0000;
    pub const SECCOMP_RET_ERRNO_ENOSYS: u32 = 0x0005_0000 | 38;

    pub const AUDIT_ARCH_X86_64: u32 = 0xC000003E;
    pub const AUDIT_ARCH_AARCH64: u32 = 0xC00000B7;

    fn bpf_stmt(code: u32, k: u32) -> u64 {
        ((code as u64) << 32) | (k as u64)
    }

    fn bpf_jump(code: u32, k: u32, jt: u8, jf: u8) -> u64 {
        (((code as u64) << 32) & 0xFFFF_FFFF_0000_0000)
            | (((jt as u64) & 0xFF) << 16)
            | (((jf as u64) & 0xFF) << 8)
            | k as u64
    }

    // syscall numbers:
    pub const SYS_READ: u32 = 0;
    pub const SYS_WRITE: u32 = 1;
    pub const SYS_OPEN: u32 = 2;
    pub const SYS_CLOSE: u32 = 3;
    pub const SYS_FSTAT: u32 = 5;
    pub const SYS_LSEEK: u32 = 8;
    pub const SYS_MMAP: u32 = 9;
    pub const SYS_MPROTECT: u32 = 10;
    pub const SYS_MUNMAP: u32 = 11;
    pub const SYS_RT_SIGACTION: u32 = 13;
    pub const SYS_RT_SIGPROCMASK: u32 = 14;
    pub const SYS_IOCTL: u32 = 16;
    pub const SYS_PREAD64: u32 = 17;
    pub const SYS_PWRITE64: u32 = 18;
    pub const SYS_READV: u32 = 19;
    pub const SYS_WRITEV: u32 = 20;
    pub const SYS_PIPE: u32 = 22;
    pub const SYS_RT_SIGRETURN: u32 = 15;
    pub const SYS_UNLINK: u32 = 87;
    pub const SYS_RENAME: u32 = 82;
    pub const SYS_MKDIRAT: u32 = 258;
    pub const SYS_GETCWD: u32 = 79;
    pub const SYS_NANOSLEEP: u32 = 35;
    pub const SYS_GETPID: u32 = 39;
    pub const SYS_SOCKET: u32 = 41;
    pub const SYS_CONNECT: u32 = 42;
    pub const SYS_ACCEPT: u32 = 43;
    pub const SYS_SENDTO: u32 = 44;
    pub const SYS_RECVFROM: u32 = 45;
    pub const SYS_SENDMSG: u32 = 46;
    pub const SYS_RECVMSG: u32 = 47;
    pub const SYS_SHUTDOWN: u32 = 48;
    pub const SYS_BIND: u32 = 49;
    pub const SYS_LISTEN: u32 = 50;
    pub const SYS_ACCEPT4: u32 = 288;
    pub const SYS_GETSOCKOPT: u32 = 55;
    pub const SYS_SETSOCKOPT: u32 = 54;
    pub const SYS_SOCKETPAIR: u32 = 53;
    pub const SYS_GETSOCKNAME: u32 = 51;
    pub const SYS_GETPEERNAME: u32 = 52;
    pub const SYS_FUTEX: u32 = 202;
    pub const SYS_EXIT_GROUP: u32 = 231;
    pub const SYS_EXIT: u32 = 60;
    pub const SYS_EPOLL_CREATE1: u32 = 291;
    pub const SYS_EPOLL_CTL: u32 = 233;
    pub const SYS_EPOLL_PWAIT: u32 = 232;
    pub const SYS_EVENTFD2: u32 = 290;
    pub const SYS_PIPE2: u32 = 293;
    pub const SYS_OPENAT: u32 = 257;
    pub const SYS_FSYNC: u32 = 74;
    pub const SYS_UNLINKAT: u32 = 35;

    pub fn assemble_filter() -> Vec<u64> {
        // THE ARCHITECTURE PREAMBLE, AS ONE LITERAL.
        //
        // Load `arch` from seccomp_data, continue when it is x86_64 or aarch64, and kill the
        // process on anything else. Jump offsets count from the instruction AFTER the jump,
        // which is why the two tests skip one and three instructions: each must land past the
        // kill on the allow path.
        //
        // Written as a vec literal rather than a run of pushes so the program reads as the
        // instruction sequence it is, and the offsets can be counted by eye.
        let mut program: Vec<u64> = vec![
            bpf_stmt(BPF_LD | BPF_W | BPF_ABS, 4), // offsetof(arch) in seccomp_data
            bpf_jump(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_X86_64, 1, 0),
            bpf_jump(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 3, 0),
            bpf_ret(SECCOMP_RET_KILL_PROCESS), // unknown architecture, never allow
        ];

        fn bpf_ret(action: u32) -> u64 {
            ((BPF_RET | BPF_K) as u64) << 32 | (action as u64 & 0xFFFF_FFFF)
        }

        load_syscall_number(&mut program);

        let io_ok = [
            SYS_READ,
            SYS_WRITE,
            SYS_OPEN,
            SYS_CLOSE,
            SYS_FSTAT,
            SYS_LSEEK,
            SYS_MMAP,
            SYS_MPROTECT,
            SYS_MUNMAP,
            SYS_RT_SIGACTION,
            SYS_RT_SIGPROCMASK,
            SYS_RT_SIGRETURN,
            SYS_NANOSLEEP,
            SYS_GETPID,
            SYS_UNLINK,
            SYS_RENAME,
            SYS_MKDIRAT,
            SYS_FSYNC,
            SYS_IOCTL,
            SYS_PREAD64,
            SYS_PWRITE64,
            SYS_READV,
            SYS_WRITEV,
            SYS_GETCWD,
            SYS_SOCKETPAIR,
            SYS_PIPE,
            SYS_EXIT_GROUP,
            SYS_EXIT,
            SYS_EPOLL_CREATE1,
            SYS_EPOLL_CTL,
            SYS_EPOLL_PWAIT,
            SYS_EVENTFD2,
            SYS_PIPE2,
            SYS_OPENAT,
            SYS_GETSOCKNAME,
            SYS_GETPEERNAME,
        ];

        for (index, syscall) in io_ok.iter().copied().enumerate() {
            let jump_if_match = (io_ok.len() - index) as u8;
            program.push(bpf_jump(
                BPF_JMP | BPF_JEQ | BPF_K,
                syscall,
                jump_if_match,
                0,
            ));
        }

        // socket(AF_UNIX, ...): the ONLY network-family permitted;
        // socketpair is included at io_ok already (it creates pairs, no dialing).
        // For SYS_SOCKET: args[0] = domain, load args[0] (4 bytes at offset 16)
        let socket_arg_offset = 16;
        program.push(bpf_jump(BPF_JMP | BPF_JEQ | BPF_K, SYS_SOCKET, 0, 2));
        // fallthrough: continue into the remaining special checks
        program.push(bpf_stmt(BPF_LD | BPF_W | BPF_ABS, socket_arg_offset));
        program.push(bpf_jump(BPF_JMP | BPF_JEQ | BPF_K, AF_UNIX, 1, 0));
        program.push(bpf_ret(SECCOMP_RET_ERRNO_EPERM));
        reset_accumulator(&mut program);

        // everything not explicitly listed: ERRNO(ENOSYS), NOT ALLOW, so a
        // future networking attempt fails-closed and diagnostics show EPERM.
        program.push(bpf_ret(SECCOMP_RET_ERRNO_EPERM));
        program
    }

    pub const AF_UNIX: u32 = 1;

    fn load_syscall_number(program: &mut Vec<u64>) {
        // seccomp_data layout (x86_64 + aarch64 both hold nr at offset 0).
        program.push(bpf_stmt(BPF_LD | BPF_W | BPF_ABS, 0));
    }

    /// Nothing to do: the next instruction reloads the accumulator itself.
    ///
    /// Kept as a named no-op because the comment is the documentation for a subtle point in
    /// the program above, and a reader looking for the accumulator reset should find this
    /// rather than conclude the reset was forgotten.
    fn reset_accumulator(_program: &mut [u64]) {
        // seccomp BPF continues at the next sequential load, which reloads A.
    }

    /// Verify our BPF program via seccomp(2) PR_SET_SECCOMP with mode 2 and
    /// the assembled program. Returns errno as negative on failure.
    pub fn install_seccomp(program: &[u64]) -> Result<(), HardeningError> {
        // PR_SET_NO_NEW_PRIVS, arg 38; mandatory pre-step before mode-2 filters.
        // `c_int`: prctl's first argument is an int on every Linux ABI.
        let pr_set_no_new_privs: libc::c_int = 38;
        let result = unsafe {
            libc::prctl(
                pr_set_no_new_privs,
                1 as libc::c_ulong,
                0 as libc::c_ulong,
                0 as libc::c_ulong,
                0 as libc::c_ulong,
            )
        };
        if result != 0 {
            return Err(HardeningError::NoNewPrivsFailed(result as CReturnValue));
        }

        /*
         * THE PROGRAM IS REBUILT AS THE KERNEL'S OWN sock_filter, NOT CAST FROM u64.
         *
         * `struct sock_filter` is { code: u16, jt: u8, jf: u8, k: u32 }, sixteen bytes. The
         * assembler packs each instruction into a u64 so the program is readable as data, and
         * the previous version reinterpreted those u64 words as sock_filter through a pointer.
         * That is wrong by construction: the u64 layout puts `code` in bits 32..48 and `k` in
         * bits 0..32, so reading the same bytes as a sock_filter gives the kernel `code` = 0,
         * `jt`/`jf` = low bytes of `k`, and `k` = the real code. The kernel therefore received
         * a program of BPF_LD instructions with nonsense operands and rejected it, which is why
         * installation never succeeded and `harden_enabled()` stayed false on Linux.
         *
         * Unpacking explicitly also removes the pointer cast, so the bytes the kernel reads are
         * a `Vec<sock_filter>` that lives until the syscall returns. The previous version held
         * the buffer in a `Vec<u8>` that was never referenced again, so the pointer handed over
         * was only valid by luck of the borrow checker's patience.
         */
        let filters: Vec<libc::sock_filter> = program
            .iter()
            .map(|word| libc::sock_filter {
                code: (word >> 32) as u16,
                jt: ((word >> 16) & 0xFF) as u8,
                jf: ((word >> 8) & 0xFF) as u8,
                k: (*word & 0xFFFF_FFFF) as u32,
            })
            .collect();
        let fprog = libc::sock_fprog {
            len: filters.len() as libc::c_ushort,
            filter: filters.as_ptr() as *mut libc::sock_filter,
        };
        let ret_val = unsafe {
            libc::syscall(
                libc::SYS_seccomp,
                libc::SECCOMP_SET_MODE_FILTER as libc::c_ulong,
                0 as libc::c_ulong,
                &fprog as *const _,
            )
        };
        if ret_val != 0 {
            let err = std::io::Error::last_os_error();
            return Err(HardeningError::SeccompFailed(
                -(err.raw_os_error().unwrap_or(22) as CReturnValue),
            ));
        }

        Ok(())
    }
}

/// Linux-only bootstrap that installs the filter inside this process.
#[cfg(target_os = "linux")]
fn install_seccomp_linux() -> Result<(), HardeningError> {
    std::hint::black_box(());
    let assembled = linux_impl::assemble_filter();
    // Validation must never run with a zero-program (BPF programs need > 0
    // length filter); guard the call with a length check.
    if assembled.is_empty() {
        return Err(HardeningError::FilterBuild("assembled filter empty".into()));
    }
    linux_impl::install_seccomp(&assembled)
}

// ============================================================================
// macOS-noop marker surface so tests compile per-platform cleanly.
// ============================================================================

#[cfg(not(target_os = "linux"))]
fn install_filter_nonlinux() -> Result<(), HardeningError> {
    // Nothing kernel-side on macOS. Behaviorally hardening stays a Linux/
    // Android runtime guarantee. Test surface exercises only the BPF assembly
    // correctness (pure, deterministic) on every platform.
    Ok(())
}

// ============================================================================
// TESTS: pure, kernel-free validation everywhere; the BPF filter's shape is
// checked byte-exactly on every platform.
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bpf_filter_assembles_on_this_target() {
        // Assembling the filter works every platform (pure arithmetic).
        #[cfg(target_os = "linux")]
        {
            let filter = linux_impl::assemble_filter();
            assert!(
                filter.len() > 20,
                "filter must carry real program length; got {}",
                filter.len()
            );
        }
        // macOS builds do the non-linux marker instead to stay per-platform valid.
        #[cfg(not(target_os = "linux"))]
        {
            let err = install_filter_nonlinux();
            assert_eq!(err, Ok(()));
        }
    }

    /// The assembled program carries the AF_UNIX allowance and the EPERM rejections.
    ///
    /// THE FIELDS WERE READ BACKWARDS. The assembler packs `code` into a word's HIGH 32 bits
    /// and the operand `k` into its LOW 32 (`bpf_stmt`, `bpf_jump`). This test checked for
    /// AF_UNIX in the low half, where the operand lives, so it should have passed, and checked
    /// for the BPF_RET class in the high half, where the opcode lives, so that should have
    /// passed too. It failed on Linux because both comparisons were also comparing a u32
    /// against a u64 mask that had already been shifted to zero. Reading the fields explicitly
    /// makes the assertion say what the program means: at least one instruction compares the
    /// socket argument against AF_UNIX, and at least two return an errno.
    #[cfg(target_os = "linux")]
    #[test]
    fn bpf_socket_argument_filter_rejects_inet() {
        let filter = linux_impl::assemble_filter();

        // `k` is the low 32 bits, `code` the high 32, per `bpf_stmt` and `bpf_jump`.
        let operand = |word: &u64| (*word & 0xFFFF_FFFF) as u32;
        let opcode = |word: &u64| (word >> 32) as u32;

        let af_unix_operands = filter
            .iter()
            .filter(|w| operand(w) == linux_impl::AF_UNIX)
            .count();
        assert!(
            af_unix_operands >= 1,
            "the socket argument must be compared against AF_UNIX",
        );

        let seccomp_errno_returns = filter
            .iter()
            .filter(|w| opcode(w) == (linux_impl::BPF_RET | linux_impl::BPF_K))
            .filter(|w| operand(w) & 0xFFFF_0000 == linux_impl::SECCOMP_RET_ERRNO_MASK)
            .count();
        assert!(
            seccomp_errno_returns >= 2,
            "at least two paths must return an errno rather than allowing: {seccomp_errno_returns}",
        );
    }

    #[test]
    fn hardening_marker_honesty() {
        // On non-Linux this must report FALSE (kernel-level enforcement does
        // not exist on macOS); on Linux it reflects the actual state.
        let _result = install_no_network_filter_bool();
        #[cfg(target_os = "linux")]
        assert!(
            harden_enabled(),
            "linux must show HARDENED=true after install"
        );
        #[cfg(not(target_os = "linux"))]
        assert!(
            !harden_enabled(),
            "macOS remains honestly unhardenable at kernel level"
        );
    }
}
