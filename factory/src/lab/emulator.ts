import { spawn } from "node:child_process";
import { exec, sleep } from "../lib/exec.js";

export interface EmulatorConfig {
  avdName: string;
  emulatorPath?: string;
  headless?: boolean;
  port?: number;
  wipeData?: boolean;
  extraArgs?: string[];
}

export interface BootedEmulator {
  serial: string;
  avdName: string;
  processId: number | null;
}

const DEFAULT_EMULATOR = `${process.env.HOME}/Library/Android/sdk/emulator/emulator`;
const DEFAULT_ADB = `${process.env.HOME}/Library/Android/sdk/platform-tools/adb`;

export async function listAvds(emulatorPath = DEFAULT_EMULATOR): Promise<string[]> {
  const result = await exec(emulatorPath, ["-list-avds"], { timeoutMs: 15_000 });
  if (result.code !== 0) return [];
  return result.stdout
    .toString("utf8")
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

export async function findRunningEmulatorSerial(port = 5554): Promise<string | null> {
  const result = await exec(DEFAULT_ADB, ["devices"], { timeoutMs: 10_000 });
  if (result.code !== 0) return null;
  const serial = `emulator-${port}`;
  const connected = result.stdout
    .toString("utf8")
    .split("\n")
    .some((line) => line.startsWith(`${serial}\t`) || line.startsWith(`${serial} `));
  return connected ? serial : null;
}

/**
 * Boots the named AVD headless if no emulator is already attached. Returns the
 * serial so the rest of the lab can target it. Safe to call repeatedly: an
 * already-running emulator on the port is reused rather than duplicated.
 */
export async function ensureEmulator(config: EmulatorConfig): Promise<BootedEmulator> {
  const port = config.port ?? 5554;
  const existing = await findRunningEmulatorSerial(port);
  if (existing) {
    await waitForDeviceReady(existing);
    return { serial: existing, avdName: config.avdName, processId: null };
  }

  const emulatorPath = config.emulatorPath ?? DEFAULT_EMULATOR;
  const args = ["-avd", config.avdName, "-port", String(port)];
  if (config.headless !== false) {
    args.push("-no-window", "-no-audio", "-no-boot-anim", "-gpu", "swiftshader_indirect");
  }
  if (config.wipeData) args.push("-wipe-data");
  if (config.extraArgs) args.push(...config.extraArgs);

  const child = spawn(emulatorPath, args, {
    detached: true,
    stdio: "ignore",
  });
  child.unref();

  const serial = `emulator-${port}`;
  await waitForDeviceReady(serial);
  return { serial, avdName: config.avdName, processId: child.pid ?? null };
}

async function waitForDeviceReady(serial: string, timeoutMs = 180_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
    const devices = await exec(DEFAULT_ADB, ["devices"], { timeoutMs: 10_000 });
    const attached =
      devices.code === 0 &&
      devices.stdout
        .toString("utf8")
        .split("\n")
        .some((line) => line.startsWith(`${serial}\t`) || line.startsWith(`${serial} `));

    if (attached) {
      const booted = await exec(DEFAULT_ADB, ["-s", serial, "shell", "getprop", "sys.boot_completed"], {
        timeoutMs: 10_000,
      });
      if (booted.code === 0 && booted.stdout.toString("utf8").trim() === "1") return;
    }
    await sleep(1_500);
  }

  throw new Error(`Emulator ${serial} was not ready within ${timeoutMs}ms`);
}

export async function shutdownEmulator(serial: string): Promise<void> {
  await exec(DEFAULT_ADB, ["-s", serial, "emu", "kill"], { timeoutMs: 15_000 }).catch(() => undefined);
}
