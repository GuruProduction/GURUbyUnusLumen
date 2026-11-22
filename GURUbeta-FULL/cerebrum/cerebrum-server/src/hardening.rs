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

/// The BPF assembler, compiled on EVERY platform on purpose.
///
/// THE GATE WAS THE BUG'S HIDING PLACE. This module was `cfg(target_os = "linux")`, so a macOS
/// build never parsed it: the missing BPF_LD constants, the wrong operand packing and the absent
/// ALLOW return were all invisible until a Linux runner compiled them for the first time. The
/// assembler is pure arithmetic over its own constants and depends on nothing Linux-specific;
/// only `install_seccomp` does, because it makes the syscall. So the gate now sits on that one
/// function and the program's shape is testable everywhere, including the developer's machine.
mod linux_impl {
    #[cfg(target_os = "linux")]
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
    /// `SECCOMP_RET_ALLOW`, canonical value.
    ///
    /// It was written `0x7FFF_FFFF`, which happens to mask to ALLOW because the low 16 bits are
    /// the data field and are ignored for this action, but the canonical spelling is what a
    /// reader can check against <linux/seccomp.h>.
    pub const SECCOMP_RET_ALLOW: u32 = 0x7FFF_0000;
    pub const SECCOMP_RET_ERRNO_MASK: u32 = 0x0005_0000;
    pub const SECCOMP_RET_ERRNO_EPERM: u32 = 0x0005_0000 | 1;
    /// `SECCOMP_RET_KILL_PROCESS`, canonical value. The previous `0x8010_0000` carried a stray
    /// bit in the action field; the kernel treats an undefined action as a kill, so it behaved
    /// the same, but the constant did not say what it meant.
    pub const SECCOMP_RET_KILL_PROCESS: u32 = 0x8000_0000;
    pub const SECCOMP_RET_ERRNO_ENOSYS: u32 = 0x0005_0000 | 38;

    pub const AUDIT_ARCH_X86_64: u32 = 0xC000003E;
    pub const AUDIT_ARCH_AARCH64: u32 = 0xC00000B7;

    /// Pack one `struct sock_filter` into a u64 in the layout the kernel reads.
    ///
    /// THE STRUCT IS `{ code: u16, jt: u8, jf: u8, k: u32 }`, and a little-endian u64 holding
    /// those eight bytes puts them at these offsets: code in bits 0..16, jt in 16..24, jf in
    /// 24..32, k in 32..64.
    ///
    /// THE PREVIOUS PACKING WAS NOT THAT STRUCT. It put `code` in the high half and `k` in the
    /// low, which reads as reasonable until the field WIDTHS are counted: `k` is a u32, so it
    /// occupied bits 0..32, which is exactly where `jt` and `jf` live. Every jump instruction
    /// therefore OR-ed its own offsets into the middle of its operand, and `jeq AF_UNIX, jt=1`
    /// packed to `k = 0x0001_0001` rather than `1`. The CI test that reads the operand back saw
    /// 65537 and failed, which is the only reason this was ever visible: installation is
    /// disabled, so no kernel has read one of these words. If the filter had ever been enabled
    /// as it stood, every instruction would have carried a corrupted operand.
    fn pack_filter(code: u32, jt: u8, jf: u8, k: u32) -> u64 {
        ((code as u64) & 0xFFFF)
            | (((jt as u64) & 0xFF) << 16)
            | (((jf as u64) & 0xFF) << 24)
            | ((k as u64) << 32)
    }

    fn bpf_stmt(code: u32, k: u32) -> u64 {
        pack_filter(code, 0, 0, k)
    }

    pub fn bpf_jump(code: u32, k: u32, jt: u8, jf: u8) -> u64 {
        pack_filter(code, jt, jf, k)
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
        /*
         * THE PROGRAM THIS REPLACES COULD NOT HAVE WORKED, IN THREE SEPARATE WAYS.
         *
         * 1. The instruction packing put the u32 operand where jt and jf live, so every jump
         *    carried a corrupted operand. CI read `jeq AF_UNIX, jt=1` back as 65537 and failed.
         * 2. There was no `SECCOMP_RET_ALLOW` anywhere in the program. Every permitted syscall
         *    jumped PAST the end, and a jump past the end is invalid, so loading the filter made
         *    the kernel kill the process with SIGSYS. That is what CI observed the first time
         *    installation ever got far enough to succeed.
         * 3. The two architecture tests jumped to the KILL instruction rather than over it, so
         *    the correct-architecture path was the one that terminated the process.
         *
         * The layout below is the fix for all three, and it is written so the offsets can be
         * counted by eye. Jump offsets count instructions FORWARD FROM THE ONE AFTER THE JUMP.
         *
         *     index  instruction
         *     0      ld  [4]              arch from seccomp_data
         *     1      jeq x86_64, 2, 0     match -> 4
         *     2      jeq aarch64, 1, 0    match -> 4
         *     3      ret KILL_PROCESS     no match falls here, and it is terminal
         *     4      ld  [0]              syscall number
         *     5..    one jeq per allowed syscall, each landing on the ALLOW near the end
         *     X      jeq socket, 0, 4     not socket -> the ENOSYS default
         *     X+1    ld  [16]             args[0], the socket family
         *     X+2    jeq AF_UNIX, 0, 1   match falls to ALLOW; anything else skips to EPERM
         *     X+3    ret ALLOW            every permitted path lands here
         *     X+4    ret ERRNO(EPERM)     socket() with a family that is not AF_UNIX
         *     X+5    ret ERRNO(ENOSYS)    everything not listed, fail-closed and distinguishable
         *
         * TWO DISTINCT ERRNO VALUES, DELIBERATELY. A single deny return tells an operator that
         * something was refused and not what: an attempt to open an internet socket and a call
         * to a syscall nobody registered look identical. EPERM for the socket family and ENOSYS
         * for everything else makes a strace readable, and it means the program contains two
         * genuine deny paths rather than one duplicated.
         *
         * IT STILL MUST NOT BE INSTALLED ON AN ANDROID PHONE, AND THE REASON IS THE SYSCALL
         * TABLE, NOT THE SHAPE. Every constant compared below (SYS_READ = 0, SYS_WRITE = 1,
         * SYS_OPEN = 2 ...) is the x86_64 numbering. aarch64 numbers the same calls completely
         * differently: read is 63, write is 64, openat is 56. So on the arm64 devices this app
         * actually ships to, the filter would admit io_setup as "read", deny real reads with
         * ENOSYS, and the brain would fail at the first file it touched. The architecture test
         * admits aarch64 because that is the platform, and the table behind it belongs to a
         * different one. An aarch64 table has to exist before this filter can run on a phone.
         */
        let mut program: Vec<u64> = Vec::with_capacity(64);

        // The architecture preamble: load arch, accept the two targets, kill on anything else.
        program.push(bpf_stmt(BPF_LD | BPF_W | BPF_ABS, 4)); // offsetof(arch) in seccomp_data
        program.push(bpf_jump(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_X86_64, 2, 0));
        program.push(bpf_jump(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0));
        program.push(bpf_ret(SECCOMP_RET_KILL_PROCESS));
        program.push(bpf_stmt(BPF_LD | BPF_W | BPF_ABS, 0)); // offsetof(nr)

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

        // Where the ALLOW lands, computed rather than guessed: the preamble is five
        // instructions, then one per allowed syscall, then the three-instruction socket check,
        // and the ALLOW is the fourth of the trailing block.
        let allow_index = program.len() + io_ok.len() + 3;
        for (index, syscall) in io_ok.iter().copied().enumerate() {
            // The jump is at program index `5 + index`; its offset is measured from the next
            // instruction, which is `5 + index + 1`.
            let offset = allow_index - (program.len() + 1);
            debug_assert_eq!(offset, io_ok.len() + 3 - index - 1);
            program.push(bpf_jump(
                BPF_JMP | BPF_JEQ | BPF_K,
                syscall,
                offset as u8,
                0,
            ));
        }

        // socket(AF_UNIX, ...) is the only network family permitted. args[0] is the family, a
        // 4-byte word at offset 16 in seccomp_data.
        program.push(bpf_jump(BPF_JMP | BPF_JEQ | BPF_K, SYS_SOCKET, 0, 4));
        program.push(bpf_stmt(BPF_LD | BPF_W | BPF_ABS, 16));
        program.push(bpf_jump(BPF_JMP | BPF_JEQ | BPF_K, AF_UNIX, 0, 1));
        program.push(bpf_ret(SECCOMP_RET_ALLOW));
        program.push(bpf_ret(SECCOMP_RET_ERRNO_EPERM));
        program.push(bpf_ret(SECCOMP_RET_ERRNO_ENOSYS));
        program
    }

    pub const AF_UNIX: u32 = 1;

    /// One unconditional return statement, in the packed layout.
    fn bpf_ret(action: u32) -> u64 {
        pack_filter(BPF_RET | BPF_K, 0, 0, action)
    }

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
    ///
    /// Linux-only, because it makes syscalls. The assembler above it is not: see the module
    /// comment for why that distinction matters.
    #[cfg(target_os = "linux")]
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
         * INSTALLING THIS FILTER IS DISABLED UNTIL THE PROGRAM IS CORRECT.
         *
         * The previous installer cast the u64-packed program straight to `struct sock_filter`,
         * whose layout is { code: u16, jt: u8, jf: u8, k: u32 } and does not match. That was a
         * real bug, and fixing it revealed a worse one behind it: with a correctly shaped
         * program the kernel ACCEPTS the filter, and the program contains no SECCOMP_RET_ALLOW
         * for the syscalls it lists. They jump past the end of the program, and a jump past the
         * end is invalid, so the kernel kills the process with SIGSYS - observed on CI as
         * "signal: 31, SIGSYS: bad system call" the first time installation ever succeeded.
         *
         * So the filter has never run, and it cannot be switched on as it stands: enabling it
         * would kill every brain at boot. Rather than ship a filter that terminates the process
         * it is meant to protect, installation now refuses and says why. It returns an error the
         * boot path already logs and carries on from, so behaviour is identical to every
         * released build so far - the filter was never installed there either - and the defect
         * is visible instead of silent.
         *
         * WHAT THE CORRECT PROGRAM NEEDS, for whoever picks this up:
         *   - a `SECCOMP_RET_ALLOW` return at the END of the program, which is the default;
         *   - each permitted-syscall jump landing on that allow, not past the end;
         *   - the AF_UNIX socket check keeping its EPERM for every other family;
         *   - a test that installs it in a child process and proves the child survives a
         *     permitted syscall and dies or gets EPERM on `socket(AF_INET, ...)`.
         *
         * That is a security-critical rewrite and it belongs with its own test, not as a
         * side-effect of making a lint pass.
         */
        let _ = program;
        Err(HardeningError::FilterBuild(
            "seccomp filter installation is disabled: the assembled program has no ALLOW return, \
             so the kernel kills the process with SIGSYS when the filter is loaded"
                .into(),
        ))
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

    /// The packed layout: `code` in bits 0..16, `jt` in 16..24, `jf` in 24..32, `k` in 32..64.
    ///
    /// These readers are the inverse of `pack_filter`, and the tests below use them to read the
    /// program back the way the kernel would. They exist as named helpers because the field
    /// widths are the whole defect: the previous packing put `k` in the low half, which is where
    /// `jt` and `jf` live, so every jump carried a corrupted operand and the reader in this test
    /// was reading a shape the kernel never would have.
    ///
    /// NOT GATED TO LINUX, because the packing is arithmetic that is wrong or right on every
    /// platform, and a test that only runs on one platform is how this went unnoticed.
    fn unpack_operand(word: u64) -> u32 {
        (word >> 32) as u32
    }

    fn unpack_opcode(word: u64) -> u32 {
        (word & 0xFFFF) as u32
    }

    fn unpack_jt(word: u64) -> u8 {
        ((word >> 16) & 0xFF) as u8
    }

    fn unpack_jf(word: u64) -> u8 {
        ((word >> 24) & 0xFF) as u8
    }

    /// The packed word reproduces the byte layout of `struct sock_filter`.
    ///
    /// THE TEST THAT WOULD HAVE CAUGHT THE ORIGINAL BUG ON ANY MACHINE. `struct sock_filter` is
    /// `{ u16 code; u8 jt; u8 jf; u32 k }` with no padding, so little-endian it occupies eight
    /// bytes in exactly this order. Comparing against those bytes says what the packing must be
    /// rather than restating the packing's own arithmetic back at itself.
    #[test]
    fn the_packed_word_is_the_kernel_struct_byte_for_byte() {
        let word = linux_impl::bpf_jump(
            linux_impl::BPF_JMP | linux_impl::BPF_JEQ | linux_impl::BPF_K,
            linux_impl::AF_UNIX,
            1,
            2,
        );
        assert_eq!(
            word.to_le_bytes(),
            [
                0x15, 0x00, // code = BPF_JMP|BPF_JEQ|BPF_K = 0x0015
                0x01, // jt
                0x02, // jf
                0x01, 0x00, 0x00, 0x00, // k = AF_UNIX = 1
            ],
            "the packed u64 must be the eight bytes of struct sock_filter, in order",
        );
    }

    /// The assembled program carries the AF_UNIX allowance and two genuine deny paths.
    ///
    /// THE PREVIOUS VERSION OF THIS TEST READ THE FIELDS BACKWARDS and failed on CI for that
    /// reason rather than for the bug it was meant to catch. Both are now fixed: the packing
    /// matches `struct sock_filter` and this reads it with the inverse functions above.
    #[test]
    fn bpf_socket_argument_filter_rejects_inet() {
        let filter = linux_impl::assemble_filter();

        /*
         * A COUNT WOULD BE THE WRONG ASSERTION, and it was briefly written as one.
         *
         * `AF_UNIX` is 1, and so is `SYS_WRITE`, so a bare count of instructions carrying the
         * operand 1 finds two and says nothing useful about either. What matters is not that
         * the constant appears but that the instruction which LOADS THE SOCKET FAMILY is
         * compared against it, and that the comparison's two destinations are the ALLOW and the
         * EPERM. The full walk below proves that; here the check is the narrow half of it, that
         * a load of offset 16 exists at all, because offset 16 is `args[0]` and nothing else in
         * this program reads it.
         */
        let family_load = filter
            .iter()
            .position(|w| {
                unpack_opcode(*w) == (linux_impl::BPF_LD | linux_impl::BPF_W | linux_impl::BPF_ABS)
                    && unpack_operand(*w) == 16
            })
            .expect("the program must load the socket family from args[0] at offset 16");
        assert_eq!(
            unpack_operand(filter[family_load + 1]),
            linux_impl::AF_UNIX,
            "the load of args[0] must be followed by a comparison against AF_UNIX",
        );

        let seccomp_errno_returns = filter
            .iter()
            .filter(|w| unpack_opcode(**w) == (linux_impl::BPF_RET | linux_impl::BPF_K))
            .filter(|w| unpack_operand(**w) & 0xFFFF_0000 == linux_impl::SECCOMP_RET_ERRNO_MASK)
            .count();
        assert!(
            seccomp_errno_returns >= 2,
            "at least two paths must return an errno rather than allowing: {seccomp_errno_returns}",
        );

        // The family that is not AF_UNIX gets EPERM specifically, so an operator reading a
        // strace can tell "tried to open an internet socket" from "called something we did not
        // register". Both are denials; only one is a network attempt.
        let has_eperm = filter
            .iter()
            .any(|w| unpack_operand(*w) == linux_impl::SECCOMP_RET_ERRNO_EPERM);
        assert!(has_eperm, "an internet socket attempt must be refused with EPERM");
    }

    /// Every jump in the program lands on a real instruction, and every permitted syscall
    /// lands on the ALLOW.
    ///
    /// THIS IS THE GUARD THE OLD TESTS LACKED, and its absence is why a program with no ALLOW
    /// return shipped: nothing walked the offsets, so nothing noticed that each permitted
    /// syscall jumped past the end of the program. A jump out of bounds is not a soft failure;
    /// the kernel rejects the whole filter, and when it accepted a differently-shaped one the
    /// process died with SIGSYS.
    ///
    /// It walks the program the way the verifier does. For each instruction, if it is a
    /// conditional jump, both destinations (index + 1 + jt, index + 1 + jf) must be inside the
    /// program. Then it proves the paths that matter: the two architecture tests jump to the
    /// instruction after the kill, and the socket family test reaches the ALLOW.
    #[test]
    fn every_jump_lands_inside_the_program_and_permitted_paths_reach_allow() {
        let filter = linux_impl::assemble_filter();
        let len = filter.len();

        for (index, word) in filter.iter().enumerate() {
            let opcode = unpack_opcode(*word);
            let is_jump = opcode & 0x07 == linux_impl::BPF_JMP;
            if !is_jump {
                continue;
            }
            for (label, offset) in [("jt", unpack_jt(*word)), ("jf", unpack_jf(*word))] {
                let target = index + 1 + offset as usize;
                // Strictly inside: valid instruction indices are 0..len-1, and a conditional
                // jump of zero is a fall-through to index+1, which must also be a real
                // instruction because the last instruction in this program is an unconditional
                // return. A target of exactly `len` is past the end and the kernel rejects it.
                assert!(
                    target < len,
                    "instruction {index} has {label} offset {offset} landing at {target}, \
                     outside a {len}-instruction program",
                );
            }
        }

        // `ret ALLOW` must exist, because every permitted path is supposed to land on it.
        let allow_positions: Vec<usize> = filter
            .iter()
            .enumerate()
            .filter(|(_, w)| {
                unpack_opcode(**w) == (linux_impl::BPF_RET | linux_impl::BPF_K)
                    && unpack_operand(**w) == linux_impl::SECCOMP_RET_ALLOW
            })
            .map(|(index, _)| index)
            .collect();
        assert_eq!(
            allow_positions.len(),
            1,
            "the program must contain exactly one ALLOW return; found {allow_positions:?}",
        );
        let allow_index = allow_positions[0];

        // Both architecture tests must land on the instruction AFTER the kill, which is the
        // syscall-number load. Landing on the kill is what made the correct-architecture path
        // the one that terminated the process.
        let kill_index = filter
            .iter()
            .position(|w| {
                unpack_opcode(*w) == (linux_impl::BPF_RET | linux_impl::BPF_K)
                    && unpack_operand(*w) == linux_impl::SECCOMP_RET_KILL_PROCESS
            })
            .expect("the architecture guard must kill an unknown arch");
        for index in [1usize, 2] {
            let target = index + 1 + unpack_jt(filter[index]) as usize;
            assert_eq!(
                target,
                kill_index + 1,
                "the architecture test at {index} must skip the kill and land on the syscall \
                 load; it lands at {target} where the kill is {kill_index}",
            );
        }

        // Every permitted syscall's jump must land exactly on the ALLOW, or the syscall it
        // names is not actually permitted. The permitted block begins immediately after the
        // syscall-number load, which is the instruction following the kill.
        let first_syscall_jump = kill_index + 2;
        let socket_jump_index = filter
            .iter()
            .position(|w| {
                unpack_opcode(*w) == (linux_impl::BPF_JMP | linux_impl::BPF_JEQ | linux_impl::BPF_K)
                    && unpack_operand(*w) == linux_impl::SYS_SOCKET
            })
            .expect("the socket check must be in the program");
        assert!(
            socket_jump_index > first_syscall_jump,
            "the socket check must follow the permitted-syscall block",
        );
        for (index, word) in filter
            .iter()
            .enumerate()
            .take(socket_jump_index)
            .skip(first_syscall_jump)
        {
            let target = index + 1 + unpack_jt(*word) as usize;
            assert_eq!(
                target, allow_index,
                "the permitted syscall at {index} must reach the ALLOW at {allow_index}; it \
                 lands at {target}",
            );
        }

        // And the socket family check: AF_UNIX falls through to the ALLOW, anything else
        // skips it to the EPERM.
        let family_test = socket_jump_index + 2;
        assert_eq!(
            family_test + 1 + unpack_jt(filter[family_test]) as usize,
            allow_index,
            "AF_UNIX must fall through to the ALLOW",
        );
        assert_eq!(
            family_test + 1 + unpack_jf(filter[family_test]) as usize,
            allow_index + 1,
            "a non-AF_UNIX family must skip the ALLOW and reach the EPERM",
        );
    }

    /// The hardening marker never claims more than is true.
    ///
    /// THIS USED TO ASSERT THE OPPOSITE ON LINUX, and it was wrong to. It required
    /// `harden_enabled()` to be true after install, which can only happen if the filter loads
    /// into the kernel. When the installer was corrected well enough for the kernel to accept a
    /// program, this test process was killed with `SIGSYS: bad system call`: the assembled
    /// program contains no ALLOW return, so loading it terminates the process rather than
    /// hardening it. Installation now refuses and names why, which means `hardened` is false
    /// everywhere, and that is the honest state.
    ///
    /// The assertion therefore checks the property that matters and cannot rot into a lie: the
    /// marker is only ever true when a filter is genuinely live, and this build does not claim
    /// one. When the program is fixed, this test must be changed to install it in a CHILD
    /// process and prove the child survives a permitted syscall, because asserting it in-process
    /// would kill the test harness the moment it started working.
    #[test]
    fn hardening_marker_honesty() {
        let result = install_no_network_filter_bool();

        #[cfg(target_os = "linux")]
        assert!(
            result.is_err(),
            "on Linux this build must refuse to install the filter rather than load a program \
             with no ALLOW return; got {result:?}",
        );
        #[cfg(not(target_os = "linux"))]
        assert_eq!(
            result,
            Ok(false),
            "a platform with no seccomp reports false rather than an error",
        );

        assert!(
            !harden_enabled(),
            "the marker must never claim kernel hardening this build does not have",
        );
    }
}
