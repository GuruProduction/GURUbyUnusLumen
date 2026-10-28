import { spawn, type ChildProcess } from "node:child_process";
import { EventEmitter } from "node:events";

const DEFAULT_ADB = `${process.env.HOME}/Library/Android/sdk/platform-tools/adb`;

export interface LogcatEntry {
  timestamp: string;
  pid: string;
  tid: string;
  priority: string;
  tag: string;
  message: string;
  raw: string;
  receivedAt: number;
}

export type CrashKind = "fatal_exception" | "anr" | "native_crash";

export interface CrashSignal {
  kind: CrashKind;
  packageName: string | null;
  entry: LogcatEntry;
  context: LogcatEntry[];
}

export interface LogcatWatcherOptions {
  serial: string;
  adbPath?: string;
  ringSize?: number;
  contextLines?: number;
  /**
   * When set, crash signals are only emitted when the crash belongs to this
   * package. System-process crashes (permission controller, system_server,
   * launcher) are ignored, which is the difference between a real GURU bug and
   * emulator noise.
   */
  filterPackage?: string;
  /**
   * Grace period after a crash signature appears, letting the following
   * "Process:" and stack-trace lines land in the ring before the signal is
   * built. Without this the package is often still unknown at detection time.
   */
  settleMs?: number;
  /**
   * Entries received within this window after start() are buffered but cannot
   * fire a crash signal. `adb logcat` flushes whatever is already in the
   * device buffer when it attaches, and without this drain barrier a crash
   * from a previous run gets attributed to the run that just started.
   */
  drainMs?: number;
}

/**
 * Precise attribution: only a crash line whose PID equals the app's PID is a
 * real app crash. Emulator system processes (uiautomator, permission
 * controller, system_server) crash independently and their stack traces often
 * mention the foreground app, so name-matching is not enough.
 */


interface PendingCrash {
  kind: CrashKind;
  entry: LogcatEntry;
  timer: NodeJS.Timeout;
}

/**
 * Streams `adb logcat` continuously, keeps a bounded ring buffer, and emits a
 * crash signal whenever a FATAL EXCEPTION, ANR, or native crash signature
 * appears. The ring buffer is what a repro bundle serialises when a signal
 * fires.
 */
export class LogcatWatcher extends EventEmitter {
  private readonly serial: string;
  private readonly adbPath: string;
  private readonly ringSize: number;
  private readonly contextLines: number;
  private readonly filterPackage: string | null;
  private readonly settleMs: number;
  private readonly drainMs: number;
  private readonly ring: LogcatEntry[] = [];
  private child: ChildProcess | null = null;
  private partial = "";
  private startedAt = 0;
  private pending: PendingCrash | null = null;
  private crashPid: number | null = null;

  constructor(options: LogcatWatcherOptions) {
    super();
    this.serial = options.serial;
    this.adbPath = options.adbPath ?? DEFAULT_ADB;
    this.ringSize = options.ringSize ?? 4_000;
    this.contextLines = options.contextLines ?? 80;
    this.filterPackage = options.filterPackage ?? null;
    this.settleMs = options.settleMs ?? 900;
    this.drainMs = options.drainMs ?? 750;
  }

  get entries(): readonly LogcatEntry[] {
    return this.ring;
  }

  start(): void {
    if (this.child) return;
    this.startedAt = Date.now();
    const child = spawn(this.adbPath, ["-s", this.serial, "logcat", "-v", "threadtime"], {
      stdio: ["ignore", "pipe", "pipe"],
    });
    this.child = child;

    child.stdout?.setEncoding("utf8");
    child.stdout?.on("data", (chunk: string) => this.consume(chunk));
    child.stderr?.setEncoding("utf8");
    child.stderr?.on("data", () => {
      // adb occasionally writes daemon noise to stderr; ignore.
    });
    child.on("exit", () => {
      if (this.child === child) this.child = null;
    });
  }

  async stop(): Promise<void> {
    if (this.pending) {
      clearTimeout(this.pending.timer);
      this.pending = null;
    }
    if (!this.child) return;
    const child = this.child;
    this.child = null;
    child.kill("SIGTERM");
    await new Promise((resolve) => {
      const timer = setTimeout(resolve, 2_000);
      child.once("exit", () => {
        clearTimeout(timer);
        resolve(null);
      });
    });
  }

  /**
   * Locks crash attribution to one process. Call after launching the app, and
   * again after any relaunch, with the app's current PID. A null argument
   * disables PID locking and falls back to package-name attribution.
   */
  setCrashPid(pid: number | null): void {
    this.crashPid = pid;
  }

  snapshot(): LogcatEntry[] {
    return [...this.ring];
  }

  since(timestampMs: number): LogcatEntry[] {
    return this.ring.filter((entry) => entry.receivedAt >= timestampMs);
  }

  mark(): number {
    return Date.now();
  }

  private consume(chunk: string): void {
    this.partial += chunk;
    const lines = this.partial.split("\n");
    this.partial = lines.pop() ?? "";

    for (const line of lines) {
      const entry = parseLogcatLine(line);
      if (!entry) continue;
      this.ring.push(entry);
      if (this.ring.length > this.ringSize) this.ring.shift();
      this.emit("entry", entry);
      this.detectCrash(entry);
    }
  }

  private detectCrash(entry: LogcatEntry): void {
    // Backlog drain window: buffer the entry, never signal on it.
    if (Date.now() - this.startedAt < this.drainMs) return;

    const message = entry.message;

    if (message.includes("FATAL EXCEPTION")) {
      this.scheduleCrash("fatal_exception", entry);
      return;
    }
    if (message.includes("ANR in ") || message.includes("Application Not Responding")) {
      this.scheduleCrash("anr", entry);
      return;
    }
    if (entry.tag === "libc" && message.includes("Fatal signal")) {
      this.scheduleCrash("native_crash", entry);
      return;
    }
    if (entry.tag === "DEBUG" && message.includes("pid: ") && message.includes(">>> ")) {
      this.scheduleCrash("native_crash", entry);
    }
  }

  /**
   * Deferred emission: wait for the trailing Process:/stack lines, then build
   * the signal and apply the package filter. Only the first crash of a run is
   * kept per detection window; the runner treats the first as the failure.
   */
  private scheduleCrash(kind: CrashKind, entry: LogcatEntry): void {
    if (this.pending) return;
    const timer = setTimeout(() => {
      const pending = this.pending;
      this.pending = null;
      if (!pending) return;
      const context = this.buildContext(pending.entry);
      const packageName = inferPackage(context);
      if (!this.belongsToFilter(context, packageName, pending.entry)) return;
      const signal: CrashSignal = { kind: pending.kind, packageName, entry: pending.entry, context };
      this.emit("crash", signal);
    }, this.settleMs);
    this.pending = { kind, entry, timer };
  }

  private buildContext(entry: LogcatEntry): LogcatEntry[] {
    const index = this.ring.indexOf(entry);
    if (index === -1) {
      return this.ring.slice(Math.max(0, this.ring.length - this.contextLines));
    }
    const start = Math.max(0, index - this.contextLines);
    return this.ring.slice(start, index + 1);
  }

  private belongsToFilter(
    context: LogcatEntry[],
    inferred: string | null,
    entry: LogcatEntry,
  ): boolean {
    if (this.crashPid !== null) {
      return normalizePid(entry.pid) === this.crashPid;
    }
    if (!this.filterPackage) return true;
    if (inferred === this.filterPackage) return true;
    return false;
  }
}

function normalizePid(pid: string): number | null {
  if (!pid) return null;
  const value = Number(pid);
  return Number.isFinite(value) ? value : null;
}

function inferPackage(entries: LogcatEntry[]): string | null {
  const processPattern = /Process:\s*([A-Za-z0-9_.]+)/;
  const anrPattern = /ANR in ([A-Za-z0-9_.]+)/;

  for (let i = entries.length - 1; i >= 0; i -= 1) {
    const processMatch = processPattern.exec(entries[i].message);
    if (processMatch) return processMatch[1];
    const anrMatch = anrPattern.exec(entries[i].message);
    if (anrMatch) return anrMatch[1];
  }
  return null;
}

const THREADTIME_PATTERN =
  /^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]*?)\s*:\s(.*)$/;

function parseLogcatLine(line: string): LogcatEntry | null {
  const match = THREADTIME_PATTERN.exec(line);
  if (!match) {
    if (line.trim().length === 0) return null;
    return {
      timestamp: "",
      pid: "",
      tid: "",
      priority: "",
      tag: "",
      message: line,
      raw: line,
      receivedAt: Date.now(),
    };
  }
  return {
    timestamp: match[1],
    pid: match[2],
    tid: match[3],
    priority: match[4],
    tag: match[5].trim(),
    message: match[6],
    raw: line,
    receivedAt: Date.now(),
  };
}
