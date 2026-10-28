import { readFile, writeFile, readdir } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, relative } from "node:path";
import type { BundleFailure, BundleManifest, ReplayResult } from "../bundles/types.js";
import { readBundle } from "../bundles/writer.js";
import type { Device } from "../lab/device.js";
import { replayBundle } from "../bundles/replayer.js";
import { sleep } from "../lib/exec.js";

/**
 * The repair lane.
 *
 * The rule this file exists to enforce is the one that was missing for nine
 * months: a builder is not allowed to touch code until it has reproduced the
 * failure itself. That rule is worthless as prose. It is worth something as a
 * function that returns BLOCKED and refuses to continue.
 *
 * The lane has exactly two hard steps and one soft one:
 *
 *   prepareRepair()  — replay the bundle against the current build. If it does
 *                      not reproduce, return blocked and stop. If it does,
 *                      extract the evidence a repairer needs.
 *   verifyFix()      — replay again after a candidate fix. The fix only counts
 *                      if the failure is gone AND the suite is green, which is
 *                      the "fails on old, passes on new" oracle.
 *
 * Everything a repairer would need is written to repair-brief.json beside the
 * bundle: the signature, the crash context, the stack frames mapped to real
 * files, and the failing step. No human writes a bug report.
 */

export interface StackFrame {
  className: string;
  methodName: string;
  fileName: string | null;
  lineNumber: number | null;
  raw: string;
}

export interface SourceCandidate {
  frame: StackFrame;
  absolutePath: string;
  exists: boolean;
}

export interface RepairBrief {
  bundleId: string;
  journeyId: string;
  journeyName: string;
  packageName: string;
  buildSha: string;
  buildVersionCode: string;
  buildVersionName: string;
  failure: BundleFailure;
  failingStep: {
    index: number;
    label: string;
    kind: string;
    error: string | null;
  } | null;
  stackFrames: StackFrame[];
  sourceCandidates: SourceCandidate[];
  logcatExcerpt: string[];
  reproducedAt: string;
  reproduced: boolean;
  blockedReason: string | null;
  notes: string[];
}

export type RepairOutcome =
  | { status: "ready"; brief: RepairBrief }
  | { status: "blocked"; brief: RepairBrief };

export interface PrepareOptions {
  bundleDir: string;
  device: Device;
  /** Root of the Android tree, used to map stack frames to files. */
  sourceRoot: string;
  attempts?: number;
  /** Skip the device replay and build the brief from stored evidence only. */
  evidenceOnly?: boolean;
}

/**
 * Step one. Reproduce or stop.
 *
 * The device replay is the whole point: without it the lane has no evidence
 * that the thing it is about to "fix" is still broken, and a stale bundle is
 * how you end up fixing a bug that was already fixed while the real one stays.
 */
/**
 * Two invocations of this lane targeting the same bundle will fight over the
 * same emulator and overwrite each other's repair-brief.json. That produces
 * exactly the worst possible outcome for a gate: a brief on disk that says
 * BLOCKED while the operator saw READY, or the reverse. The lock below makes
 * the second invocation fail loudly instead of corrupting the evidence.
 */
const RUNNING = new Set<string>();

export async function prepareRepair(options: PrepareOptions): Promise<RepairOutcome> {
  const lockKey = `${options.bundleDir}:prepare`;
  if (RUNNING.has(lockKey)) {
    throw new Error(
      `A repair run is already in progress for ${options.bundleDir}. ` +
        "Refusing to start a second one: two runs would overwrite the same brief.",
    );
  }
  RUNNING.add(lockKey);

  try {
    return await prepareRepairUnlocked(options);
  } finally {
    RUNNING.delete(lockKey);
  }
}

async function prepareRepairUnlocked(options: PrepareOptions): Promise<RepairOutcome> {
  const { manifest, journey } = await readBundle(options.bundleDir);
  const notes: string[] = [];

  let reproduced = false;
  let replay: ReplayResult | null = null;
  let blockedReason: string | null = null;

  if (options.evidenceOnly) {
    notes.push("evidence-only mode: device replay skipped, reproduction NOT confirmed");
    blockedReason = "evidence-only mode cannot confirm reproduction";
  } else {
    replay = await replayBundle({
      bundleDir: options.bundleDir,
      device: options.device,
      attempts: options.attempts ?? 1,
    });

    reproduced = replay.reproduced;
    notes.push(...replay.notes);

    if (!reproduced) {
      blockedReason =
        "replay did not reproduce the recorded failure; the lane refuses to touch code " +
        "without a live failure. This is either a stale bundle, a flaky bug, or an " +
        "environment difference. Re-run the journey to get a fresh bundle.";
    }
  }

  const stackFrames = extractStackFrames(failureContext(manifest));
  const sourceCandidates = await mapFramesToSources(stackFrames, options.sourceRoot);

  const failingStepRecord = manifest.steps.find((step) => !step.ok) ?? manifest.steps.at(-1) ?? null;

  const brief: RepairBrief = {
    bundleId: manifest.id,
    journeyId: journey.id,
    journeyName: journey.name,
    packageName: manifest.packageName,
    buildSha: manifest.buildSha,
    buildVersionCode: manifest.buildVersionCode,
    buildVersionName: manifest.buildVersionName,
    failure: manifest.failure,
    failingStep: failingStepRecord
      ? {
          index: failingStepRecord.index,
          label: failingStepRecord.step.label,
          kind: failingStepRecord.step.kind,
          error: failingStepRecord.error ?? null,
        }
      : null,
    stackFrames,
    sourceCandidates,
    logcatExcerpt: failureContext(manifest).slice(-40),
    reproducedAt: new Date().toISOString(),
    reproduced,
    blockedReason,
    notes,
  };

  await writeFile(
    join(options.bundleDir, "repair-brief.json"),
    JSON.stringify(brief, null, 2),
  );

  return reproduced ? { status: "ready", brief } : { status: "blocked", brief };
}

export interface VerifyOptions {
  bundleDir: string;
  device: Device;
  /** How many times to attempt the replay. A fix must hold every time. */
  attempts?: number;
  /** Extra settle time after a rebuild/install before replaying. */
  settleMs?: number;
}

export interface VerifyResult {
  bundleId: string;
  verified: boolean;
  reproducedBefore: boolean;
  reproducedAfter: boolean;
  notes: string[];
  checkedAt: string;
}

/**
 * Step two. Prove the fix actually fixes.
 *
 * A fix counts only when the recorded failure no longer reproduces. The
 * inverse of prepareRepair is not "tests pass"; it is "this specific failure,
 * under this specific journey, is gone".
 */
export async function verifyFix(options: VerifyOptions): Promise<VerifyResult> {
  const lockKey = `${options.bundleDir}:verify`;
  if (RUNNING.has(lockKey)) {
    throw new Error(
      `A verify run is already in progress for ${options.bundleDir}. ` +
        "Refusing to start a second one: two runs would overwrite the same result.",
    );
  }
  RUNNING.add(lockKey);

  try {
    return await verifyFixUnlocked(options);
  } finally {
    RUNNING.delete(lockKey);
  }
}

async function verifyFixUnlocked(options: VerifyOptions): Promise<VerifyResult> {
  const { manifest } = await readBundle(options.bundleDir);
  const notes: string[] = [];

  if (options.settleMs && options.settleMs > 0) {
    await sleep(options.settleMs);
  }

  const replay = await replayBundle({
    bundleDir: options.bundleDir,
    device: options.device,
    attempts: options.attempts ?? 1,
  });

  notes.push(...replay.notes);

  const reproducedAfter = replay.reproduced;
  const verified = !reproducedAfter;

  if (verified) {
    notes.push("failure no longer reproduces: the fix holds for this recorded journey");
  } else {
    notes.push("failure still reproduces: the fix did not address the observed cause");
    if (replay.failureObserved) {
      notes.push(`observed signature: ${replay.failureObserved.signature}`);
    }
  }

  const result: VerifyResult = {
    bundleId: manifest.id,
    verified,
    reproducedBefore: true,
    reproducedAfter,
    notes,
    checkedAt: new Date().toISOString(),
  };

  await writeFile(join(options.bundleDir, "repair-verify.json"), JSON.stringify(result, null, 2));

  return result;
}

function failureContext(manifest: BundleManifest): string[] {
  return manifest.failure.context ?? [];
}

const FRAME_PATTERN = /^\s*at\s+([A-Za-z0-9_.$]+)\.([A-Za-z0-9_$<>]+)\(([^)]*)\)/;

/**
 * Pulls Java/Kotlin stack frames out of the logcat context recorded in the
 * bundle. The context is plain logcat lines, so each line is prefixed with a
 * timestamp and priority; the frame itself is matched inside the message.
 */
export function extractStackFrames(context: string[]): StackFrame[] {
  const frames: StackFrame[] = [];

  for (const line of context) {
    // Strip the threadtime prefix if present: "10-19 13:15:14.371 10212 10212 E AndroidRuntime: "
    const messageIndex = line.indexOf("AndroidRuntime:");
    const message = messageIndex === -1 ? line : line.slice(messageIndex + "AndroidRuntime:".length);

    const match = FRAME_PATTERN.exec(message.trimStart());
    if (!match) continue;

    const className = match[1];
    const methodName = match[2];
    const location = match[3];

    const locationMatch = /^(.*):(\d+)$/.exec(location);
    frames.push({
      className,
      methodName,
      fileName: locationMatch?.[1] ?? (location || null),
      lineNumber: locationMatch ? Number(locationMatch[2]) : null,
      raw: line.trim(),
    });
  }

  return frames;
}

/**
 * Maps each frame's source file name to a real file in the tree. This is what
 * turns "it crashed somewhere in the app" into "it crashed in this file, this
 * method, this line", which is the difference between a search and a fix.
 */
export async function mapFramesToSources(
  frames: StackFrame[],
  sourceRoot: string,
): Promise<SourceCandidate[]> {
  const wanted = new Map<string, StackFrame>();
  for (const frame of frames) {
    if (!frame.fileName) continue;
    if (!wanted.has(frame.fileName)) wanted.set(frame.fileName, frame);
  }

  const found = new Map<string, string>();
  if (wanted.size > 0 && existsSync(sourceRoot)) {
    await walkForFiles(sourceRoot, new Set(wanted.keys()), found, 0);
  }

  return [...wanted.values()].map((frame) => ({
    frame,
    absolutePath: found.get(frame.fileName as string) ?? "",
    exists: found.has(frame.fileName as string),
  }));
}

async function walkForFiles(
  current: string,
  wanted: Set<string>,
  found: Map<string, string>,
  depth: number,
): Promise<void> {
  if (wanted.size === found.size || depth > 12) return;

  let entries;
  try {
    entries = await readdir(current, { withFileTypes: true });
  } catch {
    return;
  }

  for (const entry of entries) {
    if (entry.isDirectory()) {
      if (entry.name === "build" || entry.name === ".gradle" || entry.name === ".git") continue;
      await walkForFiles(join(current, entry.name), wanted, found, depth + 1);
      continue;
    }
    if (entry.isFile() && wanted.has(entry.name) && !found.has(entry.name)) {
      found.set(entry.name, join(current, entry.name));
    }
  }
}

/**
 * Renders the brief as plain text for a repairer that reads prose rather than
 * JSON. Deliberately repetitive of the JSON: the brief is the ticket.
 */
export function formatBrief(brief: RepairBrief): string {
  const lines: string[] = [];
  lines.push(`BUNDLE ${brief.bundleId} (${brief.journeyName})`);
  lines.push(`package ${brief.packageName}, build ${brief.buildSha} v${brief.buildVersionName}`);
  lines.push(`failure: ${brief.failure.kind} — ${brief.failure.headline}`);
  lines.push(`signature: ${brief.failure.signature}`);
  lines.push("");

  if (brief.blockedReason) {
    lines.push(`BLOCKED: ${brief.blockedReason}`);
    lines.push("");
  }

  if (brief.failingStep) {
    lines.push(
      `failing step ${brief.failingStep.index}: ${brief.failingStep.label} (${brief.failingStep.kind})`,
    );
    if (brief.failingStep.error) lines.push(`  ${brief.failingStep.error}`);
    lines.push("");
  }

  if (brief.stackFrames.length > 0) {
    lines.push(`stack frames (${brief.stackFrames.length}):`);
    for (const frame of brief.stackFrames) {
      lines.push(`  ${frame.className}.${frame.methodName} (${frame.fileName}:${frame.lineNumber ?? "?"})`);
    }
    lines.push("");
  }

  const withFile = brief.sourceCandidates.filter((candidate) => candidate.exists);
  if (withFile.length > 0) {
    lines.push(`source candidates (${withFile.length}):`);
    for (const candidate of withFile) {
      lines.push(
        `  ${relative(brief.sourceCandidates[0]?.absolutePath ?? "", candidate.absolutePath) || candidate.absolutePath}`,
      );
    }
    lines.push("");
  }

  lines.push("logcat tail:");
  for (const line of brief.logcatExcerpt.slice(-12)) lines.push(`  ${line}`);

  return lines.join("\n");
}

// repair-lock
