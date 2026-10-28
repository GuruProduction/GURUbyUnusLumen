import { spawn } from "node:child_process";

export interface ExecResult {
  stdout: Buffer;
  stderr: string;
  code: number;
  durationMs: number;
  command: string;
}

export interface ExecOptions {
  timeoutMs?: number;
  input?: string | Buffer;
  cwd?: string;
  env?: NodeJS.ProcessEnv;
}

export class ExecError extends Error {
  readonly result: ExecResult;

  constructor(message: string, result: ExecResult) {
    super(message);
    this.name = "ExecError";
    this.result = result;
  }
}

function shellEscape(arg: string): string {
  if (/^[A-Za-z0-9_./:=+@%-]+$/.test(arg)) return arg;
  return `'${arg.replace(/'/g, "'\\''")}'`;
}

export function renderCommand(command: string, args: string[]): string {
  return [command, ...args.map(shellEscape)].join(" ");
}

export function exec(command: string, args: string[], options: ExecOptions = {}): Promise<ExecResult> {
  const timeoutMs = options.timeoutMs ?? 60_000;
  const rendered = renderCommand(command, args);
  const startedAt = Date.now();

  return new Promise<ExecResult>((resolve, reject) => {
    let settled = false;
    let stderr = "";
    const chunks: Buffer[] = [];

    const child = spawn(command, args, {
      cwd: options.cwd,
      env: options.env ?? process.env,
      stdio: ["pipe", "pipe", "pipe"],
    });

    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      child.kill("SIGKILL");
      reject(new Error(`Command timed out after ${timeoutMs}ms: ${rendered}`));
    }, timeoutMs);

    child.stdout.on("data", (chunk: Buffer) => chunks.push(chunk));
    child.stderr.on("data", (chunk: Buffer) => {
      stderr += chunk.toString("utf8");
    });
    child.on("error", (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(error);
    });
    child.on("close", (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({
        stdout: Buffer.concat(chunks),
        stderr,
        code: code ?? -1,
        durationMs: Date.now() - startedAt,
        command: rendered,
      });
    });

    if (options.input !== undefined) child.stdin.end(options.input);
    else child.stdin.end();
  });
}

function describeFailure(result: ExecResult): string {
  const stderr = result.stderr.trim();
  const stdout = result.stdout.toString("utf8").trim();
  const detail = [stderr, stdout].filter((part) => part.length > 0).join("\n");
  return `Command failed with code ${result.code}: ${result.command}${detail ? `\n${detail}` : ""}`;
}

export async function execText(
  command: string,
  args: string[],
  options: ExecOptions = {},
): Promise<string> {
  const result = await exec(command, args, options);
  if (result.code !== 0) {
    throw new ExecError(describeFailure(result), result);
  }
  return result.stdout.toString("utf8");
}

export async function execChecked(
  command: string,
  args: string[],
  options: ExecOptions = {},
): Promise<ExecResult> {
  const result = await exec(command, args, options);
  if (result.code !== 0) {
    throw new ExecError(describeFailure(result), result);
  }
  return result;
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
