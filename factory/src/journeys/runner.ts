import { mkdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { Device } from "../lab/device.js";
import { LogcatWatcher, type CrashSignal } from "../lab/logcat.js";
import { dismissSystemDialogs } from "../lab/dialogs.js";
import type { Screenshot, UiNode } from "../lab/types.js";
import type { BundleFailure, JourneyDefinition, JourneyStep, StepRecord } from "../bundles/types.js";
import { writeBundle } from "../bundles/writer.js";
import { sleep } from "../lib/exec.js";

export interface JourneyRunOptions {
  device: Device;
  watcher: LogcatWatcher;
  journey: JourneyDefinition;
  bundlesDir: string;
  buildSha: string;
  buildVersionCode: string;
  buildVersionName: string;
  stopOnFirstFailure?: boolean;
  captureEveryStep?: boolean;
  settleMs?: number;
  /**
   * Replay mode: when false, no bundle is written. Replay must never produce a
   * bundle, or every replay of a broken bundle would litter the bundle store
   * with nested copies of itself.
   */
  writeBundleOnFailure?: boolean;
}

export interface JourneyRunResult {
  journey: JourneyDefinition;
  ok: boolean;
  failure: BundleFailure | null;
  bundleId: string | null;
  bundleDir: string | null;
  steps: StepRecord[];
  durationMs: number;
  notes: string[];
}

/**
 * Runs one journey against a device while a logcat watcher is live. On the
 * first crash signal or failed assertion the run stops, snapshots everything,
 * and writes a repro bundle. That bundle is the ticket; no human writes prose.
 */
export async function runJourney(options: JourneyRunOptions): Promise<JourneyRunResult> {
  const startedAt = Date.now();
  const steps: StepRecord[] = [];
  const notes: string[] = [];
  const screenshots = new Map<number, Screenshot>();
  const hierarchies = new Map<number, UiNode[]>();
  const captureEvery = options.captureEveryStep ?? true;
  const settleMs = options.settleMs ?? 600;

  let failure: CrashSignal | AssertionFailure | null = null;

  const onCrash = (signal: CrashSignal): void => {
    if (failure) return;
    failure = signal;
  };
  options.watcher.on("crash", onCrash);

  const startMark = options.watcher.mark();

  try {
    for (let index = 0; index < options.journey.steps.length; index += 1) {
      const step = options.journey.steps[index];
      const stepStartedAt = Date.now();
      const record: StepRecord = {
        index,
        step,
        startedAt: stepStartedAt,
        durationMs: 0,
        ok: true,
      };

      try {
        await executeStep(options.device, options.journey, step);
        if (step.kind === "launch") {
          // Lock crash attribution to the app process. Without this, a system
          // process crashing on the emulator is blamed on GURU. The PID can be
          // absent for a moment after launch, so poll briefly before giving up.
          const pid = await waitForPid(
            options.device,
            step.packageName ?? options.journey.packageName,
            8_000,
          );
          options.watcher.setCrashPid(pid);
          if (pid === null) {
            notes.push("pid lock unavailable after launch; falling back to package attribution");
          }
        }
        if (step.kind === "launch" || step.kind === "waitMs") {
          // OS dialogs (16 KB compatibility, crash dialogs) sit above the app
          // on cold start. Clear them here so an assertion step sees the app.
          await dismissSystemDialogs(options.device);
        }
      } catch (error) {
        record.ok = false;
        record.error = (error as Error).message;
        if (!failure) failure = makeAssertionFailure(step, error as Error);
        steps.push(record);
        break;
      }

      await sleep(settleMs);

      if (captureEvery && step.kind !== "screenshot") {
        screenshots.set(index, await options.device.screenshot());
        hierarchies.set(index, await options.device.uiHierarchy());
      } else if (step.kind === "screenshot") {
        screenshots.set(index, await options.device.screenshot());
        hierarchies.set(index, await options.device.uiHierarchy());
      }

      record.durationMs = Date.now() - stepStartedAt;
      steps.push(record);

      if (failure && options.stopOnFirstFailure !== false) break;
    }
  } finally {
    options.watcher.off("crash", onCrash);
  }

  const durationMs = Date.now() - startedAt;

  if (!failure) {
    return {
      journey: options.journey,
      ok: true,
      failure: null,
      bundleId: null,
      bundleDir: null,
      steps,
      durationMs,
      notes,
    };
  }

  const bundleFailure: BundleFailure = toBundleFailure(failure, new Date().toISOString());

  if (options.writeBundleOnFailure === false) {
    return {
      journey: options.journey,
      ok: false,
      failure: bundleFailure,
      bundleId: null,
      bundleDir: null,
      steps,
      durationMs,
      notes,
    };
  }

  const info = await options.device.info();
  const logcat = options.watcher.since(startMark);

  // Capture the failure state itself, as step index-1, so the bundle shows
  // exactly what the screen looked like when it went wrong.
  const failureIndex = Math.max(0, steps.length - 1);
  if (!screenshots.has(failureIndex)) {
    screenshots.set(failureIndex, await options.device.screenshot());
    hierarchies.set(failureIndex, await options.device.uiHierarchy());
  }

  const written = await writeBundle({
    rootDir: options.bundlesDir,
    journey: options.journey,
    buildSha: options.buildSha,
    buildVersionCode: options.buildVersionCode,
    buildVersionName: options.buildVersionName,
    deviceSerial: info.serial,
    deviceModel: `${info.manufacturer} ${info.model}`.trim(),
    androidRelease: info.release,
    androidSdk: info.sdk,
    failure: bundleFailure,
    steps,
    logcat,
    screenshots,
    hierarchies,
  });

  return {
    journey: options.journey,
    ok: false,
    failure: bundleFailure,
    bundleId: written.id,
    bundleDir: written.dir,
    steps,
    durationMs,
    notes,
  };
}

interface AssertionFailure {
  kind: "assertion";
  message: string;
  step: JourneyStep;
}

function makeAssertionFailure(step: JourneyStep, error: Error): AssertionFailure {
  return { kind: "assertion", message: error.message, step };
}

function toBundleFailure(
  failure: CrashSignal | AssertionFailure,
  detectedAt: string,
): BundleFailure {
  if (failure.kind === "assertion") {
    return {
      kind: "assertion",
      detectedAt,
      packageName: null,
      signature: `assertion:${failure.step.kind}:${failure.step.label}`,
      headline: `${failure.step.label}: ${failure.message}`,
      context: [failure.message],
    };
  }

  const context = failure.context.map((entry) => entry.raw);
  const signature = computeSignature(failure);
  const headline =
    failure.kind === "fatal_exception"
      ? firstExceptionLine(failure.context) ?? "FATAL EXCEPTION"
      : failure.kind === "anr"
        ? `ANR: ${failure.packageName ?? "unknown process"}`
        : `Native crash: ${failure.entry.message}`;

  return {
    kind: failure.kind,
    detectedAt,
    packageName: failure.packageName,
    signature,
    headline,
    context,
  };
}

function firstExceptionLine(context: { message: string }[]): string | null {
  for (const entry of context) {
    const trimmed = entry.message.trim();
    if (trimmed.includes("Exception") || trimmed.includes("Error:")) return trimmed;
  }
  return null;
}

function computeSignature(signal: CrashSignal): string {
  const firstFrame = signal.context.find(
    (entry) => entry.message.trim().startsWith("at ") || entry.message.includes("Exception"),
  );
  const base =
    signal.kind === "fatal_exception"
      ? firstExceptionLine(signal.context) ?? "FATAL EXCEPTION"
      : signal.kind === "anr"
        ? `ANR:${signal.packageName ?? "unknown"}`
        : `native:${signal.entry.message}`;
  const frame = firstFrame?.message.trim() ?? "";
  return `${signal.kind}|${base}|${frame}`.slice(0, 400);
}

async function executeStep(device: Device, journey: JourneyDefinition, step: JourneyStep): Promise<void> {
  switch (step.kind) {
    case "launch":
      await device.launch(step.packageName ?? journey.packageName, step.activity ?? journey.activity);
      return;
    case "forceStop":
      await device.forceStop(step.packageName ?? journey.packageName);
      return;
    case "clearData":
      await device.clearAppData(step.packageName ?? journey.packageName);
      return;
    case "tap": {
      if (step.x === undefined || step.y === undefined) {
        throw new Error(`tap step "${step.label}" has no coordinates`);
      }
      await device.tap(step.x, step.y);
      return;
    }
    case "tapText": {
      if (!step.text) throw new Error(`tapText step "${step.label}" has no text`);
      const node = await device.waitForText(step.text, step.timeoutMs ?? 15_000);
      await device.tap(node.center.x, node.center.y);
      return;
    }
    case "tapDescription": {
      if (!step.text) throw new Error(`tapDescription step "${step.label}" has no description`);
      const deadline = Date.now() + (step.timeoutMs ?? 15_000);
      while (Date.now() < deadline) {
        const nodes = await device.uiHierarchy();
        const node = nodes.find((candidate) => candidate.contentDesc.includes(step.text as string));
        if (node) {
          await device.tap(node.center.x, node.center.y);
          return;
        }
        await sleep(500);
      }
      throw new Error(`No node with content description containing "${step.text}"`);
    }
    case "typeText":
      if (step.text === undefined) throw new Error(`typeText step "${step.label}" has no text`);
      await device.typeText(step.text);
      return;
    case "key":
      if (step.key === undefined) throw new Error(`key step "${step.label}" has no key`);
      await device.pressKey(step.key);
      return;
    case "swipe":
      if (
        step.x === undefined ||
        step.y === undefined ||
        step.x2 === undefined ||
        step.y2 === undefined
      ) {
        throw new Error(`swipe step "${step.label}" is missing coordinates`);
      }
      await device.swipe(step.x, step.y, step.x2, step.y2, step.durationMs ?? 300);
      return;
    case "waitForText":
      if (!step.text) throw new Error(`waitForText step "${step.label}" has no text`);
      await device.waitForText(step.text, step.timeoutMs ?? 15_000);
      return;
    case "waitMs":
      await sleep(step.durationMs ?? 1_000);
      return;
    case "screenshot":
      return;
    case "back":
      await device.back();
      return;
    case "home":
      await device.home();
      return;
    default:
      throw new Error(`Unknown journey step kind: ${(step as JourneyStep).kind}`);
  }
}

async function waitForPid(device: Device, packageName: string, timeoutMs: number): Promise<number | null> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const pid = await device.pidOf(packageName).catch(() => null);
    if (pid !== null) return pid;
    await sleep(250);
  }
  return null;
}

export async function loadJourney(path: string): Promise<JourneyDefinition> {
  return JSON.parse(await readFile(path, "utf8")) as JourneyDefinition;
}
