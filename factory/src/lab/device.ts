import { exec, execChecked, execText, sleep } from "../lib/exec.js";
import { parseUiHierarchy } from "./hierarchy.js";
import type { AdbConfig, DeviceInfo, Screenshot, UiNode } from "./types.js";

const DEFAULT_ADB = `${process.env.HOME}/Library/Android/sdk/platform-tools/adb`;

export class Device {
  readonly serial: string;
  readonly adbPath: string;
  private readonly defaultTimeoutMs: number;

  constructor(config: AdbConfig) {
    this.serial = config.serial;
    this.adbPath = config.adbPath ?? DEFAULT_ADB;
    this.defaultTimeoutMs = config.defaultTimeoutMs ?? 60_000;
  }

  private baseArgs(args: string[]): string[] {
    return ["-s", this.serial, ...args];
  }

  async adb(args: string[], timeoutMs?: number): Promise<string> {
    return execText(this.adbPath, this.baseArgs(args), {
      timeoutMs: timeoutMs ?? this.defaultTimeoutMs,
    });
  }

  async adbRaw(args: string[], timeoutMs?: number): Promise<Buffer> {
    const result = await execChecked(this.adbPath, this.baseArgs(args), {
      timeoutMs: timeoutMs ?? this.defaultTimeoutMs,
    });
    return result.stdout;
  }

  async isConnected(): Promise<boolean> {
    const result = await exec(this.adbPath, ["devices"], { timeoutMs: 10_000 });
    if (result.code !== 0) return false;
    return result.stdout
      .toString("utf8")
      .split("\n")
      .some((line) => line.startsWith(`${this.serial}\t`) || line.startsWith(`${this.serial} `));
  }

  async waitForBoot(timeoutMs = 180_000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      try {
        const completed = (await this.adb(["shell", "getprop", "sys.boot_completed"], 10_000)).trim();
        if (completed === "1") return;
      } catch {
        // Device not ready yet; keep polling.
      }
      await sleep(1_000);
    }
    throw new Error(`Device ${this.serial} did not finish booting within ${timeoutMs}ms`);
  }

  async info(): Promise<DeviceInfo> {
    const [model, manufacturer, sdk, release, density] = await Promise.all([
      this.adb(["shell", "getprop", "ro.product.model"]),
      this.adb(["shell", "getprop", "ro.product.manufacturer"]),
      this.adb(["shell", "getprop", "ro.build.version.sdk"]),
      this.adb(["shell", "getprop", "ro.build.version.release"]),
      this.adb(["shell", "wm", "density"]),
    ]);

    return {
      serial: this.serial,
      model: model.trim(),
      manufacturer: manufacturer.trim(),
      sdk: Number(sdk.trim()),
      release: release.trim(),
      density: density.trim(),
    };
  }

  async install(apkPath: string, options: { replace?: boolean; grantPermissions?: boolean } = {}): Promise<void> {
    const args = ["install"];
    if (options.replace !== false) args.push("-r");
    if (options.grantPermissions) args.push("-g");
    args.push(apkPath);
    const result = await this.adb(args, 600_000);
    if (!result.includes("Success")) {
      throw new Error(`Install failed for ${apkPath}: ${result.trim()}`);
    }
  }

  async uninstall(packageName: string): Promise<void> {
    const result = await this.adb(["uninstall", packageName], 120_000);
    if (!result.includes("Success") && !result.includes("not installed")) {
      throw new Error(`Uninstall failed for ${packageName}: ${result.trim()}`);
    }
  }

  async pidOf(packageName: string): Promise<number | null> {
    const output = (await this.adb(["shell", "pidof", packageName])).trim();
    if (!output) return null;
    const pid = Number(output.split(/\s+/)[0]);
    return Number.isFinite(pid) ? pid : null;
  }

  async isInstalled(packageName: string): Promise<boolean> {
    const output = await this.adb(["shell", "pm", "list", "packages", packageName]);
    return output.split("\n").some((line) => line.trim() === `package:${packageName}`);
  }

  async launch(packageName: string, activity?: string): Promise<void> {
    if (activity) {
      await this.adb([
        "shell",
        "am",
        "start",
        "-n",
        `${packageName}/${activity}`,
        "-W",
      ], 60_000);
      return;
    }
    await this.adb(["shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"], 60_000);
  }

  async forceStop(packageName: string): Promise<void> {
    await this.adb(["shell", "am", "force-stop", packageName]);
  }

  async clearAppData(packageName: string): Promise<void> {
    await this.adb(["shell", "pm", "clear", packageName]);
  }

  async grant(packageName: string, permission: string): Promise<void> {
    await this.adb(["shell", "pm", "grant", packageName, permission]);
  }

  async revoke(packageName: string, permission: string): Promise<void> {
    await this.adb(["shell", "pm", "revoke", packageName, permission]);
  }

  async appOp(packageName: string, operation: string, mode: "allow" | "deny" | "ignore" = "allow"): Promise<void> {
    await this.adb(["shell", "appops", "set", packageName, operation, mode]);
  }

  async tap(x: number, y: number): Promise<void> {
    await this.adb(["shell", "input", "tap", String(x), String(y)]);
  }

  async longPress(x: number, y: number, durationMs = 800): Promise<void> {
    await this.adb(["shell", "input", "swipe", String(x), String(y), String(x), String(y), String(durationMs)]);
  }

  async swipe(x1: number, y1: number, x2: number, y2: number, durationMs = 300): Promise<void> {
    await this.adb([
      "shell",
      "input",
      "swipe",
      String(x1),
      String(y1),
      String(x2),
      String(y2),
      String(durationMs),
    ]);
  }

  async typeText(text: string): Promise<void> {
    // `input text` treats spaces as argument separators; replace with %s the
    // way adb documents, then escape shell-sensitive characters.
    const escaped = text.replace(/\s/g, "%s").replace(/(["'`$\\])/g, "\\$1");
    await this.adb(["shell", "input", "text", escaped]);
  }

  async pressKey(keyCode: number | string): Promise<void> {
    await this.adb(["shell", "input", "keyevent", String(keyCode)]);
  }

  async back(): Promise<void> {
    await this.pressKey(4);
  }

  async home(): Promise<void> {
    await this.pressKey(3);
  }

  async enter(): Promise<void> {
    await this.pressKey(66);
  }

  async wake(): Promise<void> {
    await this.pressKey(224);
  }

  async screenshot(): Promise<Screenshot> {
    const png = await this.adbRaw(["exec-out", "screencap", "-p"], 30_000);
    const size = readPngSize(png);
    return {
      png,
      width: size.width,
      height: size.height,
      capturedAt: Date.now(),
    };
  }

  async uiHierarchy(): Promise<UiNode[]> {
    let lastError: Error | null = null;
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try {
        const xml = await this.adb(["exec-out", "uiautomator", "dump", "/dev/tty"], 30_000);
        const cleaned = extractXml(xml);
        if (cleaned) return parseUiHierarchy(cleaned);
      } catch (error) {
        lastError = error as Error;
      }
      await sleep(400);
    }
    if (lastError) throw lastError;
    throw new Error("uiautomator dump produced no XML");
  }

  async windowSize(): Promise<{ width: number; height: number }> {
    const output = await this.adb(["shell", "wm", "size"]);
    const match = /(\d+)x(\d+)/.exec(output);
    if (!match) throw new Error(`Could not parse window size from: ${output.trim()}`);
    return { width: Number(match[1]), height: Number(match[2]) };
  }

  async logcatClear(): Promise<void> {
    await this.adb(["logcat", "-c"]);
  }

  async startScreenRecording(remotePath: string, timeLimitSec = 180): Promise<void> {
    await exec(this.adbPath, this.baseArgs([
      "shell",
      "screenrecord",
      "--time-limit",
      String(timeLimitSec),
      remotePath,
    ]), { timeoutMs: 10_000 }).catch(() => {
      // screenrecord blocks until done; fire and forget via shell background.
    });
    // Fire-and-forget background variant is more reliable for long journeys.
    await exec(this.adbPath, this.baseArgs([
      "shell",
      `nohup screenrecord --time-limit ${timeLimitSec} ${remotePath} >/dev/null 2>&1 &`,
    ]), { timeoutMs: 10_000 }).catch(() => undefined);
  }

  async pull(remotePath: string, localPath: string): Promise<void> {
    await execChecked(this.adbPath, this.baseArgs(["pull", remotePath, localPath]), {
      timeoutMs: 180_000,
    });
  }

  async push(localPath: string, remotePath: string): Promise<void> {
    await execChecked(this.adbPath, this.baseArgs(["push", localPath, remotePath]), {
      timeoutMs: 180_000,
    });
  }

  async shell(command: string, timeoutMs?: number): Promise<string> {
    return this.adb(["shell", command], timeoutMs);
  }

  async waitForText(fragment: string, timeoutMs = 15_000): Promise<UiNode> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const nodes = await this.uiHierarchy();
      const found = nodes.find((node) => node.text.includes(fragment));
      if (found) return found;
      await sleep(500);
    }
    throw new Error(`Timed out waiting for text containing "${fragment}"`);
  }

  async waitForPackageForeground(packageName: string, timeoutMs = 20_000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const output = await this.adb(["shell", "dumpsys", "activity", "activities"]);
      if (output.includes(`mResumedActivity`) && output.includes(packageName)) return;
      await sleep(500);
    }
    throw new Error(`Package ${packageName} never reached the foreground`);
  }
}

function extractXml(output: string): string | null {
  const start = output.indexOf("<?xml");
  if (start === -1) return null;
  const end = output.lastIndexOf("</hierarchy>");
  if (end === -1) return null;
  return output.slice(start, end + "</hierarchy>".length);
}

function readPngSize(png: Buffer): { width: number; height: number } {
  // PNG IHDR: bytes 16-19 width, 20-23 height, big endian.
  if (png.length < 24) return { width: 0, height: 0 };
  const signature = png.subarray(0, 8).toString("hex");
  if (signature !== "89504e470d0a1a0a") return { width: 0, height: 0 };
  return {
    width: png.readUInt32BE(16),
    height: png.readUInt32BE(20),
  };
}
