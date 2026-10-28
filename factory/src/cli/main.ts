#!/usr/bin/env bun
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Device } from "../lab/device.js";
import { LogcatWatcher } from "../lab/logcat.js";
import { ensureEmulator, listAvds } from "../lab/emulator.js";
import { loadJourney, runJourney } from "../journeys/runner.js";
import { replayAndReport } from "../bundles/replayer.js";
import { execChecked } from "../lib/exec.js";
import { runStubGate, scanSource, type BaselineEntry } from "../gates/stubGate.js";
import { planMutants, recoverFromJournal, runModuleMutation } from "../gates/mutation.js";
import { formatBrief, prepareRepair, verifyFix } from "../lanes/repair.js";
import { applyAndTest, briefFromDisk, proposeFix } from "../agents/fixer.js";

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const ANDROID_ROOT = join(REPO_ROOT, "GURUbeta-FULL");
const FACTORY_ROOT = join(REPO_ROOT, "factory");
const BUNDLES_DIR = join(FACTORY_ROOT, "bundles");
const JOURNEYS_DIR = join(FACTORY_ROOT, "src/journeys");
const DEFAULT_AVD = "Pixel_10_Pro_XL";
const DEFAULT_APP_ID = "com.unuslumen.app.gurubeta.debug";

interface BuildInfo {
  sha: string;
  versionCode: string;
  versionName: string;
}

async function readBuildInfo(): Promise<BuildInfo> {
  const sha = (
    await execChecked("git", ["rev-parse", "--short", "HEAD"], { cwd: REPO_ROOT })
  ).stdout
    .toString("utf8")
    .trim();

  const gradleFile = await readFile(join(ANDROID_ROOT, "app/build.gradle.kts"), "utf8");
  const versionCode = /versionCode\s*=\s*(\d+)/.exec(gradleFile)?.[1] ?? "unknown";
  const versionName = /versionName\s*=\s*"([^"]+)"/.exec(gradleFile)?.[1] ?? "unknown";
  return { sha, versionCode, versionName };
}

function parseFlags(argv: string[]): Map<string, string> {
  const flags = new Map<string, string>();
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith("--")) continue;
    const next = argv[index + 1];
    if (next === undefined || next.startsWith("--")) {
      flags.set(token.slice(2), "true");
    } else {
      flags.set(token.slice(2), next);
      index += 1;
    }
  }
  return flags;
}

async function pickSerial(): Promise<string> {
  const explicit = process.env.GURU_SERIAL;
  if (explicit) return explicit;

  const devices = await execChecked("adb", ["devices"], { timeoutMs: 10_000 });
  const attached = devices.stdout
    .toString("utf8")
    .split("\n")
    .filter((line) => /\tdevice$/.test(line))
    .map((line) => line.split("\t")[0]);

  if (attached.length === 0) throw new Error("No device attached. Boot an emulator or set GURU_SERIAL.");
  return attached[0];
}

async function commandDeviceInfo(flags: Map<string, string>): Promise<void> {
  const serial = flags.get("serial") ?? (await pickSerial());
  const device = new Device({ serial });
  const info = await device.info();
  console.log(JSON.stringify(info, null, 2));
}

async function commandBootEmulator(flags: Map<string, string>): Promise<void> {
  const avdName = flags.get("avd") ?? DEFAULT_AVD;
  const available = await listAvds();
  if (!available.includes(avdName)) {
    throw new Error(`AVD "${avdName}" not found. Available: ${available.join(", ")}`);
  }
  const booted = await ensureEmulator({ avdName });
  console.log(JSON.stringify(booted, null, 2));
}

async function commandInstall(flags: Map<string, string>): Promise<void> {
  const serial = flags.get("serial") ?? (await pickSerial());
  const apk = flags.get("apk") ?? join(ANDROID_ROOT, "app/build/outputs/apk/debug/app-debug.apk");
  if (!existsSync(apk)) throw new Error(`APK not found: ${apk}`);
  const device = new Device({ serial });
  const grant = flags.get("grant") !== "false";
  await device.install(apk, { replace: true, grantPermissions: grant });
  console.log(`Installed ${apk} on ${serial}`);
}

async function commandGrant(flags: Map<string, string>): Promise<void> {
  const serial = flags.get("serial") ?? (await pickSerial());
  const packageName = flags.get("package") ?? DEFAULT_APP_ID;
  const device = new Device({ serial });

  const permissions = [
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.USE_EXACT_ALARM",
    "android.permission.READ_CALENDAR",
    "android.permission.WRITE_CALENDAR",
    "android.permission.READ_CONTACTS",
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.READ_PHONE_STATE",
    "android.permission.CALL_PHONE",
    "android.permission.READ_SMS",
    "android.permission.SEND_SMS",
    "android.permission.READ_CALL_LOG",
    "android.permission.READ_LOGS",
  ];

  for (const permission of permissions) {
    await device.grant(packageName, permission).catch(() => undefined);
  }

  const appOps: [string, "allow" | "deny" | "ignore"][] = [
    ["android:manage_external_storage", "allow"],
    ["android:system_alert_window", "allow"],
    ["android:write_settings", "allow"],
    ["android:usage_stats", "allow"],
    ["android:request_install_packages", "allow"],
    ["android:package_verification", "allow"],
  ];

  for (const [operation, mode] of appOps) {
    await device.appOp(packageName, operation, mode).catch(() => undefined);
  }

  console.log(
    `Granted ${permissions.length} runtime permissions and ${appOps.length} app-ops on ${packageName}`,
  );
}

async function commandRunJourney(flags: Map<string, string>): Promise<void> {
  const serial = flags.get("serial") ?? (await pickSerial());
  const journeyName = flags.get("journey") ?? "guru-smoke";
  const journeyPath = flags.get("file") ?? join(JOURNEYS_DIR, "definitions", `${journeyName}.json`);
  if (!existsSync(journeyPath)) throw new Error(`Journey not found: ${journeyPath}`);

  const journey = await loadJourney(journeyPath);
  const build = await readBuildInfo();
  const device = new Device({ serial });
  // Clear the device buffer before attaching so a previous run's crash is not
  // replayed into this run's ring buffer.
  await device.logcatClear().catch(() => undefined);
  await new Promise((resolveSleep) => setTimeout(resolveSleep, 250));

  const watcher = new LogcatWatcher({ serial, filterPackage: journey.packageName });
  watcher.start();

  await mkdir(BUNDLES_DIR, { recursive: true });

  try {
    const result = await runJourney({
      device,
      watcher,
      journey,
      bundlesDir: BUNDLES_DIR,
      buildSha: build.sha,
      buildVersionCode: build.versionCode,
      buildVersionName: build.versionName,
      settleMs: Number(flags.get("settle") ?? 600),
      captureEveryStep: flags.get("capture") !== "false",
    });

    console.log(
      JSON.stringify(
        {
          journey: result.journey.id,
          ok: result.ok,
          durationMs: result.durationMs,
          steps: result.steps.length,
          failedStep: result.steps.find((step) => !step.ok)?.index ?? null,
          failure: result.failure,
          bundleId: result.bundleId,
          bundleDir: result.bundleDir,
        },
        null,
        2,
      ),
    );
  } finally {
    await watcher.stop();
  }
}

async function commandReplay(flags: Map<string, string>): Promise<void> {
  const serial = flags.get("serial") ?? (await pickSerial());
  const bundleDir = flags.get("bundle");
  if (!bundleDir) throw new Error("--bundle <dir> is required");
  const device = new Device({ serial });
  const attempts = Number(flags.get("attempts") ?? 1);

  const result = await replayAndReport({
    bundleDir: resolve(bundleDir),
    device,
    attempts,
  });

  console.log(JSON.stringify(result, null, 2));
}

async function commandListBundles(): Promise<void> {
  const { readdir } = await import("node:fs/promises");
  if (!existsSync(BUNDLES_DIR)) {
    console.log("No bundles yet.");
    return;
  }
  const dirs = (await readdir(BUNDLES_DIR, { withFileTypes: true }))
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort()
    .reverse();

  for (const dir of dirs) {
    const manifestPath = join(BUNDLES_DIR, dir, "manifest.json");
    if (!existsSync(manifestPath)) continue;
    const manifest = JSON.parse(await readFile(manifestPath, "utf8")) as {
      journeyName: string;
      failure: { kind: string; headline: string };
      createdAt: string;
    };
    console.log(
      `${dir}\t${manifest.createdAt}\t${manifest.failure.kind}\t${manifest.failure.headline}`,
    );
  }
}

async function commandBuildApk(flags: Map<string, string>): Promise<void> {
  const variant = flags.get("variant") ?? "debug";
  const task = variant === "release" ? "assembleRelease" : "assembleDebug";
  console.log(`Running ./gradlew ${task} in ${ANDROID_ROOT}`);
  const result = await execChecked("./gradlew", [task], {
    cwd: ANDROID_ROOT,
    timeoutMs: 30 * 60_000,
  });
  const output = result.stdout.toString("utf8");
  await mkdir(join(FACTORY_ROOT, "artifacts"), { recursive: true });
  await writeFile(join(FACTORY_ROOT, "artifacts", `build-${variant}.log`), output);
  console.log(output.split("\n").slice(-40).join("\n"));
}

async function commandStubGate(flags: Map<string, string>): Promise<void> {
  const root = flags.get("root") ?? ANDROID_ROOT;
  const baselinePath = join(FACTORY_ROOT, "baseline", "stub-gate.json");
  let baseline: BaselineEntry[] = [];
  if (existsSync(baselinePath)) {
    baseline = JSON.parse(await readFile(baselinePath, "utf8")) as BaselineEntry[];
  }

  const report = await runStubGate({ root, baseline });

  if (flags.get("json") === "true") {
    console.log(JSON.stringify(report, null, 2));
  } else {
    console.log(`Scanned ${report.scannedFiles} Kotlin files under ${root}`);
    console.log(
      `Findings: ${report.findings.length} total, ${report.baselined.length} baselined, ${report.newFindings.length} new`,
    );

    for (const finding of report.newFindings) {
      console.log(
        `${finding.file}:${finding.line}:${finding.column}  ${finding.rule}\n    ${finding.message}\n    ${finding.excerpt}`,
      );
    }

    if (report.errors.length > 0) {
      console.log(`Errors while reading files:`);
      for (const error of report.errors) console.log(`  ${error}`);
    }
  }

  // The gate fails when anything new is found or a file could not be read.
  // Baseline entries never fail the build; that is their whole purpose.
  if (report.newFindings.length > 0 || report.errors.length > 0) {
    process.exitCode = 1;
  }
}

async function commandStubGateSelfTest(): Promise<void> {
  const cases: { name: string; source: string; expectRule: string | null }[] = [
    {
      name: "TODO() call",
      source: 'fun f(): Int {\n    TODO("later")\n}\n',
      expectRule: "TODO_CALL",
    },
    {
      name: "throw NotImplementedError",
      source: 'fun f() {\n    throw NotImplementedError("nope")\n}\n',
      expectRule: "NOT_IMPLEMENTED",
    },
    {
      name: "empty catch one line",
      source: "fun f() {\n    try { println(1) } catch (e: Exception) { }\n}\n",
      expectRule: "EMPTY_CATCH",
    },
    {
      name: "empty catch multi line",
      source: "fun f() {\n    try {\n        println(1)\n    } catch (e: Exception) {\n    }\n}\n",
      expectRule: "EMPTY_CATCH",
    },
    {
      name: "swallowed exception",
      source:
        'fun f() {\n    try {\n        println(1)\n    } catch (e: Exception) {\n        println("failed")\n    }\n}\n',
      expectRule: "SWALLOWED_RESULT",
    },
    {
      name: "TODO comment marker",
      source: "// TODO: wire this up\nfun f() {}\n",
      expectRule: "STUB_MARKER",
    },
    {
      name: "clean code",
      source: "fun f(): Int {\n    return 2 + 2\n}\n",
      expectRule: null,
    },
    {
      name: "caught and logged",
      source:
        "fun f() {\n    try {\n        println(1)\n    } catch (e: Exception) {\n        println(e.message)\n    }\n}\n",
      expectRule: null,
    },
    {
      name: "caught and rethrown",
      source:
        "fun f() {\n    try {\n        println(1)\n    } catch (e: Exception) {\n        throw e\n    }\n}\n",
      expectRule: null,
    },
  ];

  let failures = 0;
  for (const testCase of cases) {
    const findings = scanSource(testCase.source, "selftest.kt");
    const rules = findings.map((finding) => finding.rule);
    const passed =
      testCase.expectRule === null
        ? findings.length === 0
        : rules.includes(testCase.expectRule as never);

    if (!passed) {
      failures += 1;
      console.log(`FAIL  ${testCase.name}`);
      console.log(
        `      expected ${testCase.expectRule ?? "no finding"}, got [${rules.join(", ")}]`,
      );
    } else {
      console.log(`pass  ${testCase.name}`);
    }
  }

  console.log(`\n${cases.length - failures}/${cases.length} stub-gate self-tests passed`);
  if (failures > 0) process.exitCode = 1;
}

async function commandMutation(flags: Map<string, string>): Promise<void> {
  const root = flags.get("root") ?? ANDROID_ROOT;
  const module = flags.get("module") ?? "calendar:domain";
  const maxMutants = Number(flags.get("max") ?? 12);

  // Never start a run on top of a crashed one.
  const restored = await recoverFromJournal(FACTORY_ROOT);
  if (restored.length > 0) {
    console.log(`Recovered ${restored.length} file(s) from a previous interrupted run:`);
    for (const file of restored) console.log(`  ${file}`);
  }

  console.log(`Running mutation testing on :${module} (max ${maxMutants} mutants)`);
  const report = await runModuleMutation({ root, module, maxMutants }, FACTORY_ROOT);

  const baselinePath = join(FACTORY_ROOT, "baseline", `mutation-${module.replace(/:/g, "-")}.json`);
  await mkdir(join(FACTORY_ROOT, "baseline"), { recursive: true });
  await writeFile(baselinePath, JSON.stringify(report, null, 2));

  if (flags.get("json") === "true") {
    console.log(JSON.stringify(report, null, 2));
  } else {
    console.log(`Module: ${report.module} (${report.testTask})`);
    console.log(`Planned ${report.mutantsPlanned}, ran ${report.mutantsRun}`);
    console.log(
      `Killed ${report.killed}, survived ${report.survived}, compile errors ${report.compileErrors}, timeouts ${report.timeouts}`,
    );
    console.log(`Mutation score: ${report.score === null ? "n/a" : `${report.score}%`}`);

    if (report.survivors.length > 0) {
      console.log(`\nSURVIVORS (tests did not notice these changes):`);
      for (const survivor of report.survivors) {
        console.log(`  ${survivor.file}:${survivor.line}  ${survivor.rule}`);
        console.log(`    was: ${survivor.original.trim()}`);
        console.log(`    now: ${survivor.mutated.trim()}`);
      }
    }

    console.log(`\nBaseline written to ${baselinePath}`);
  }
}

async function commandMutationSelfTest(): Promise<void> {
  const sources = new Map<string, string>([
    [
      "Example.kt",
      [
        "fun add(a: Int, b: Int): Int = a + b",
        "fun same(a: Int): Boolean = a == 4",
        "fun above(a: Int): Boolean = a > 5",
        "fun both(a: Boolean, b: Boolean): Boolean = a && b",
        "// a comment with == in it",
        "fun always(): Boolean = true",
      ].join("\n"),
    ],
  ]);

  const mutants = planMutants(sources, { maxMutants: 20 });
  const rules = new Set(mutants.map((mutant) => mutant.rule));
  const checks: [string, boolean][] = [
    ["finds an equality flip", rules.has("EQUALITY_FLIP")],
    ["finds a boundary shift", rules.has("BOUNDARY_SHIFT")],
    ["finds a logical flip", rules.has("LOGICAL_OP_FLIP")],
    ["finds a boolean return flip", rules.has("BOOLEAN_RETURN_FLIP")],
    ["ignores comment lines", mutants.every((m) => !m.original.trim().startsWith("//"))],
    ["never mutates the original line", mutants.every((m) => m.original !== m.mutated)],
    ["flip is real", mutants.some((m) => m.original.includes("==") && m.mutated.includes("!="))],
    ["boundary is real", mutants.some((m) => m.original.includes(">") && m.mutated.includes(">="))],
  ];

  let failures = 0;
  for (const [name, passed] of checks) {
    console.log(`${passed ? "pass" : "FAIL"}  ${name}`);
    if (!passed) failures += 1;
  }
  console.log(`\n${checks.length - failures}/${checks.length} mutation self-tests passed`);
  if (failures > 0) process.exitCode = 1;
}

async function commandRepair(flags: Map<string, string>): Promise<void> {
  const serial = flags.get("serial") ?? (await pickSerial());
  const bundleDir = flags.get("bundle");
  if (!bundleDir) throw new Error("--bundle <dir> is required");

  const device = new Device({ serial });
  const evidenceOnly = flags.get("evidence-only") === "true";

  const outcome = await prepareRepair({
    bundleDir: resolve(bundleDir),
    device,
    sourceRoot: ANDROID_ROOT,
    attempts: Number(flags.get("attempts") ?? 1),
    evidenceOnly,
  });

  if (flags.get("json") === "true") {
    console.log(JSON.stringify(outcome, null, 2));
  } else {
    console.log(formatBrief(outcome.brief));
    console.log("");
    console.log(
      outcome.status === "ready"
        ? "STATUS: READY - reproduction confirmed, repair may proceed"
        : "STATUS: BLOCKED - no fix is permitted without a live reproduction",
    );
  }

  if (outcome.status !== "ready") process.exitCode = 1;
}

async function commandVerifyFix(flags: Map<string, string>): Promise<void> {
  const serial = flags.get("serial") ?? (await pickSerial());
  const bundleDir = flags.get("bundle");
  if (!bundleDir) throw new Error("--bundle <dir> is required");

  const device = new Device({ serial });
  const result = await verifyFix({
    bundleDir: resolve(bundleDir),
    device,
    attempts: Number(flags.get("attempts") ?? 1),
    settleMs: Number(flags.get("settle") ?? 0),
  });

  if (flags.get("json") === "true") {
    console.log(JSON.stringify(result, null, 2));
  } else {
    console.log(`Bundle ${result.bundleId}`);
    console.log(`Reproduced before fix: ${result.reproducedBefore}`);
    console.log(`Reproduced after fix:  ${result.reproducedAfter}`);
    for (const note of result.notes) console.log(`  ${note}`);
    console.log(
      result.verified ? "VERIFIED: the failure is gone" : "NOT VERIFIED: the failure persists",
    );
  }

  if (!result.verified) process.exitCode = 1;
}

async function commandRepairSelfTest(): Promise<void> {
  const { extractStackFrames } = await import("../lanes/repair.js");

  const context = [
    "10-19 13:15:14.371 10212 10212 E AndroidRuntime: FATAL EXCEPTION: main",
    "10-19 13:15:14.371 10212 10212 E AndroidRuntime: Process: com.unuslumen.app.gurubeta.debug, PID: 10212",
    "10-19 13:15:14.371 10212 10212 E AndroidRuntime: java.lang.IllegalStateException: boom",
    "10-19 13:15:14.371 10212 10212 E AndroidRuntime: \tat com.unuslumen.app.guru.MainViewModel.load(MainViewModel.kt:88)",
    "10-19 13:15:14.371 10212 10212 E AndroidRuntime: \tat androidx.lifecycle.ViewModel.onCreate(ViewModel.java:41)",
    "10-19 13:15:14.372 10212 10212 E AndroidRuntime: \tat com.unuslumen.app.guru.MainActivity.onCreate(MainActivity.kt:200)",
  ];

  const frames = extractStackFrames(context);

  const checks: [string, boolean][] = [
    [
      "extracts app frames",
      frames.some((f) => f.className === "com.unuslumen.app.guru.MainViewModel"),
    ],
    ["extracts the method", frames.some((f) => f.methodName === "load")],
    ["extracts the file", frames.some((f) => f.fileName === "MainViewModel.kt")],
    ["extracts the line", frames.some((f) => f.lineNumber === 88)],
    ["extracts multiple frames", frames.length >= 3],
    ["keeps androidx frames", frames.some((f) => f.className.startsWith("androidx."))],
    [
      "handles a line with no frame",
      extractStackFrames([
        "10-19 13:15:14.371 10212 10212 E AndroidRuntime: java.lang.IllegalStateException: boom",
      ]).length === 0,
    ],
  ];

  let failures = 0;
  for (const [name, passed] of checks) {
    console.log(`${passed ? "pass" : "FAIL"}  ${name}`);
    if (!passed) failures += 1;
  }
  console.log(`\n${checks.length - failures}/${checks.length} repair-lane self-tests passed`);
  if (failures > 0) process.exitCode = 1;
}

async function commandFix(flags: Map<string, string>): Promise<void> {
  const bundleDir = flags.get("bundle");
  if (!bundleDir) throw new Error("--bundle <dir> is required");

  const brief = await briefFromDisk(resolve(bundleDir));

  if (!brief.reproduced) {
    console.error(
      "BLOCKED: the brief says the failure was not reproduced. No fix is permitted. " +
        "Re-run the journey to produce a live bundle first.",
    );
    process.exitCode = 1;
    return;
  }

  const module = flags.get("module") ?? "portal:domain";
  const dryRun = flags.get("dry-run") === "true";

  console.log(`Asking ${process.env.FACTORY_MODEL ?? "deepseek-v4.1-flash:cloud"} for a fix...`);
  const proposal = await proposeFix({
    brief,
    sourceRoot: ANDROID_ROOT,
    module,
    workDir: resolve(bundleDir),
    model: flags.get("model"),
    dryRun,
  });

  console.log(`\nROOT CAUSE: ${proposal.explanation}`);
  console.log(`Files proposed: ${proposal.files.length}`);
  for (const file of proposal.files) {
    console.log(`  ${file.path}`);
    console.log(`    why: ${file.reason}`);
  }

  if (proposal.files.length === 0) {
    console.log("\nThe model refused to change anything. Nothing was written.");
    process.exitCode = 1;
    return;
  }

  const attempt = await applyAndTest(
    {
      brief,
      sourceRoot: ANDROID_ROOT,
      module,
      workDir: resolve(bundleDir),
      model: flags.get("model"),
      dryRun,
    },
    proposal,
  );

  if (flags.get("json") === "true") {
    console.log(JSON.stringify({ proposal, attempt }, null, 2));
  } else {
    console.log(`\nApplied: ${attempt.applied}`);
    console.log(`Build ok: ${attempt.buildOk}`);
    console.log(`Tests ok: ${attempt.testsOk}`);
    if (attempt.error) console.log(`Error: ${attempt.error}`);
    for (const note of attempt.notes) console.log(`  ${note}`);
    console.log(
      attempt.applied && attempt.testsOk
        ? "FIX HOLDS TESTS: run 'verify-fix --bundle <dir>' to prove the failure is gone"
        : "FIX REJECTED: files restored to their original contents",
    );
  }

  if (!(attempt.applied && attempt.testsOk)) process.exitCode = 1;
}

async function commandDoctor(): Promise<void> {
  const checks: Record<string, unknown> = {};

  try {
    const devices = await execChecked("adb", ["devices"], { timeoutMs: 10_000 });
    checks.adb = devices.stdout.toString("utf8").trim();
  } catch (error) {
    checks.adb = `FAILED: ${(error as Error).message}`;
  }

  try {
    checks.avds = await listAvds();
  } catch (error) {
    checks.avds = `FAILED: ${(error as Error).message}`;
  }

  checks.androidRoot = existsSync(ANDROID_ROOT);
  checks.debugApk = existsSync(join(ANDROID_ROOT, "app/build/outputs/apk/debug/app-debug.apk"));
  checks.bundlesDir = BUNDLES_DIR;
  checks.journeys = existsSync(JOURNEYS_DIR);

  console.log(JSON.stringify(checks, null, 2));
}

function usage(): void {
  console.log(`GURU factory CLI

Commands:
  doctor                         check adb, AVDs, APKs, and directories
  boot-emulator [--avd name]     boot (or reuse) the AVD and wait for readiness
  device-info [--serial serial]  print the connected device's properties
  install [--apk path]           install the debug APK with permissions granted
  grant [--package id]           grant runtime permissions and app-ops
  build-apk [--variant debug]    run gradle assembleDebug / assembleRelease
  run-journey [--journey name] [--file path]
                                 run a journey, emit a bundle on failure
  replay --bundle <dir> [--attempts n]
                                 replay a bundle and report reproduction
  list-bundles                   list recorded repro bundles
  stub-gate [--root path] [--json true]
                                 fail on TODO(), NotImplementedError, empty
                                 catches, swallowed exceptions and stub markers
  stub-gate-self-test            prove the gate fires on real stubs
  mutation [--module name] [--max n]
                                 source-level mutation testing: change one
                                 line, run the module's real tests, report
                                 survivors (tests that noticed nothing)
  mutation-self-test             prove the mutator produces real mutations
  repair --bundle <dir> [--evidence-only true]
                                 reproduce first, then produce a repair brief.
                                 exits BLOCKED if the failure does not reproduce
  verify-fix --bundle <dir>      replay after a fix; verified only when the
                                 recorded failure is gone
  repair-self-test               prove stack-frame extraction works
  fix --bundle <dir> [--module name] [--dry-run true] [--model name]
                                 ask the model for a fix, apply it, run the
                                 module tests, restore on failure
`);
}

async function main(): Promise<void> {
  const [command, ...rest] = process.argv.slice(2);
  const flags = parseFlags(rest);

  switch (command) {
    case "doctor":
      await commandDoctor();
      return;
    case "boot-emulator":
      await commandBootEmulator(flags);
      return;
    case "device-info":
      await commandDeviceInfo(flags);
      return;
    case "install":
      await commandInstall(flags);
      return;
    case "grant":
      await commandGrant(flags);
      return;
    case "build-apk":
      await commandBuildApk(flags);
      return;
    case "run-journey":
      await commandRunJourney(flags);
      return;
    case "replay":
      await commandReplay(flags);
      return;
    case "list-bundles":
      await commandListBundles();
      return;
    case "stub-gate":
      await commandStubGate(flags);
      return;
    case "stub-gate-self-test":
      await commandStubGateSelfTest();
      return;
    case "mutation":
      await commandMutation(flags);
      return;
    case "mutation-self-test":
      await commandMutationSelfTest();
      return;
    case "repair":
      await commandRepair(flags);
      return;
    case "verify-fix":
      await commandVerifyFix(flags);
      return;
    case "repair-self-test":
      await commandRepairSelfTest();
      return;
    case "fix":
      await commandFix(flags);
      return;
    default:
      usage();
      process.exitCode = command ? 1 : 0;
  }
}

main().catch((error) => {
  console.error(`factory: ${(error as Error).message}`);
  process.exitCode = 1;
});

// bust

// fixwire
