import { readFile, writeFile, mkdir } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { createHash } from "node:crypto";
import { askModel, type ModelMessage } from "./model.js";
import type { RepairBrief } from "../lanes/repair.js";
import { exec } from "../lib/exec.js";

/**
 * The fix agent.
 *
 * This is the actor the factory was missing. Everything before this point can
 * find a bug, prove it is live, and prove a fix works. This is the part that
 * reads the brief and writes the code.
 *
 * Design choices that are deliberate:
 *
 * 1. It asks for WHOLE FILES, not diffs. A diff that fails to apply is a
 *    silent no-op; a whole-file replacement either compiles or it does not.
 * 2. It only touches files that the brief already mapped from real stack
 *    frames, plus files those files name in imports. It cannot wander the repo.
 * 3. Every original byte is journalled before a write and restored on any
 *    failure, so a bad fix cannot leave the tree broken.
 * 4. It runs the module's real tests AND the recorded replay. Passing tests
 *    that do not kill the reported failure are not a fix.
 */

export interface ProposedFile {
  path: string;
  content: string;
  reason: string;
}

export interface FixAttempt {
  bundleId: string;
  model: string;
  durationMs: number;
  filesTouched: string[];
  applied: boolean;
  buildOk: boolean;
  testsOk: boolean;
  failureGone: boolean | null;
  appliedPatchPath: string | null;
  error: string | null;
  notes: string[];
}

export interface FixOptions {
  brief: RepairBrief;
  /** Root of the Android tree. */
  sourceRoot: string;
  /** Gradle module whose tests prove the fix, e.g. portal:domain. */
  module: string;
  /** Directory to write the applied patch and the journal. */
  workDir: string;
  model?: string;
  /** Cap on how many source files are shown to the model. */
  maxFiles?: number;
  /** Skip writing to disk; produce the proposal only. */
  dryRun?: boolean;
}

interface JournalEntry {
  absolutePath: string;
  originalContent: string;
}

const SYSTEM_PROMPT = `You are fixing a real bug in an Android Kotlin app called GURU.

You will be given:
- the exact failure, its signature, and the failing step
- the stack frames, already mapped to source files
- the full current contents of the files involved

Return ONLY a JSON object, no prose, no markdown fences:

{
  "files": [
    { "path": "<absolute path exactly as given>", "content": "<the complete new file content>", "reason": "<one sentence>" }
  ],
  "explanation": "<one short paragraph naming the root cause>"
}

Rules you must not break:
- Return the COMPLETE file content for each file you change, not a diff.
- Change as few files as possible. Prefer one.
- Do not rename public APIs, do not reformat untouched code, do not add dependencies.
- Do not swallow exceptions. Do not add TODO, FIXME or placeholder code.
- Do not weaken or delete any test.
- If the real cause is not visible in the files provided, return
  {"files": [], "explanation": "INSUFFICIENT EVIDENCE: <what is missing>"} and change nothing.
- Kotlin only. Keep the existing style of the file.`;

export async function proposeFix(options: FixOptions): Promise<{
  files: ProposedFile[];
  explanation: string;
  model: string;
  durationMs: number;
}> {
  const maxFiles = options.maxFiles ?? 6;

  const seedPaths = options.brief.sourceCandidates
    .filter((candidate) => candidate.exists && candidate.absolutePath.length > 0)
    .map((candidate) => candidate.absolutePath);

  const uniquePaths = [...new Set(seedPaths)].slice(0, maxFiles);

  if (uniquePaths.length === 0) {
    throw new Error(
      "No source files were mapped from the stack frames, so there is nothing to show the model. " +
        "The brief is evidence-only or the frames did not name app files.",
    );
  }

  const fileBlocks: string[] = [];
  for (const path of uniquePaths) {
    const content = await readFile(path, "utf8");
    fileBlocks.push(`### FILE: ${path}\n\`\`\`kotlin\n${content}\n\`\`\``);
  }

  const userPrompt = [
    `FAILURE KIND: ${options.brief.failure.kind}`,
    `HEADLINE: ${options.brief.failure.headline}`,
    `SIGNATURE: ${options.brief.failure.signature}`,
    options.brief.failingStep
      ? `FAILING STEP: ${options.brief.failingStep.label} (${options.brief.failingStep.kind})${
          options.brief.failingStep.error ? ` — ${options.brief.failingStep.error}` : ""
        }`
      : "",
    "",
    `STACK FRAMES:`,
    ...options.brief.stackFrames.map(
      (f) => `  ${f.className}.${f.methodName} (${f.fileName}:${f.lineNumber ?? "?"})`,
    ),
    "",
    `LOGCAT TAIL:`,
    ...options.brief.logcatExcerpt.slice(-25).map((line) => `  ${line}`),
    "",
    `FILES:`,
    ...fileBlocks,
  ]
    .filter((line) => line !== "")
    .join("\n");

  const messages: ModelMessage[] = [
    { role: "system", content: SYSTEM_PROMPT },
    { role: "user", content: userPrompt },
  ];

  const reply = await askModel(messages, {
    model: options.model,
    temperature: 0.1,
    timeoutMs: 600_000,
  });

  return { ...parseProposal(reply.content, uniquePaths), model: reply.model, durationMs: reply.durationMs };
}

/**
 * Parses the model's JSON. Models wrap JSON in fences or add a sentence before
 * it often enough that a strict JSON.parse alone is not good enough, so the
 * first balanced object is extracted.
 */
export function parseProposal(
  raw: string,
  allowedPaths: string[],
): { files: ProposedFile[]; explanation: string } {
  const text = raw.trim();
  const start = text.indexOf("{");
  if (start === -1) throw new Error(`Model did not return JSON. First 200 chars: ${text.slice(0, 200)}`);

  let depth = 0;
  let end = -1;
  let inString = false;
  let escaped = false;

  for (let index = start; index < text.length; index += 1) {
    const character = text[index];
    if (escaped) {
      escaped = false;
      continue;
    }
    if (character === "\\") {
      escaped = true;
      continue;
    }
    if (character === '"') {
      inString = !inString;
      continue;
    }
    if (inString) continue;
    if (character === "{") depth += 1;
    else if (character === "}") {
      depth -= 1;
      if (depth === 0) {
        end = index;
        break;
      }
    }
  }

  if (end === -1) throw new Error("Model returned unbalanced JSON");

  const parsed = JSON.parse(text.slice(start, end + 1)) as {
    files?: { path?: string; content?: string; reason?: string }[];
    explanation?: string;
  };

  const allowed = new Set(allowedPaths);
  const files: ProposedFile[] = [];

  for (const file of parsed.files ?? []) {
    if (!file.path || typeof file.content !== "string") continue;
    // The model is not allowed to write outside the files it was shown.
    if (!allowed.has(file.path)) continue;
    files.push({ path: file.path, content: file.content, reason: file.reason ?? "" });
  }

  return { files, explanation: parsed.explanation ?? "" };
}

export async function applyAndTest(
  options: FixOptions,
  proposal: { files: ProposedFile[]; explanation: string; model: string },
): Promise<FixAttempt> {
  const notes: string[] = [];
  const journal: JournalEntry[] = [];
  const filesTouched = proposal.files.map((file) => file.path);

  await mkdir(options.workDir, { recursive: true });

  const startedAt = Date.now();
  let applied = false;
  let buildOk = false;
  let testsOk = false;
  let error: string | null = null;
  let appliedPatchPath: string | null = null;

  // An empty proposal must never be reported as an applied, passing fix. If
  // the model changed nothing, the suite would pass against untouched code and
  // the caller would be told the bug was fixed. That is the exact false
  // success this factory exists to eliminate.
  if (proposal.files.length === 0) {
    return {
      bundleId: options.brief.bundleId,
      model: proposal.model,
      durationMs: Date.now() - startedAt,
      filesTouched: [],
      applied: false,
      buildOk: false,
      testsOk: false,
      failureGone: null,
      appliedPatchPath: null,
      error: proposal.explanation.length > 0
        ? `no files proposed: ${proposal.explanation}`
        : "no files proposed",
      notes: ["nothing was written; an empty fix is not a fix"],
    };
  }

  try {
    for (const file of proposal.files) {
      const original = await readFile(file.path, "utf8");
      journal.push({ absolutePath: file.path, originalContent: original });
    }

    if (options.dryRun) {
      notes.push("dry run: no files written");
      return {
        bundleId: options.brief.bundleId,
        model: proposal.model,
        durationMs: Date.now() - startedAt,
        filesTouched,
        applied: false,
        buildOk: false,
        testsOk: false,
        failureGone: null,
        appliedPatchPath: null,
        error: null,
        notes,
      };
    }

    for (const file of proposal.files) {
      await mkdir(dirname(file.path), { recursive: true });
      await writeFile(file.path, file.content);
    }
    applied = true;

    // Save the applied content so a human (or a later lane) can read exactly
    // what changed even after the journal is cleared.
    appliedPatchPath = join(options.workDir, "applied-fix.json");
    await writeFile(
      appliedPatchPath,
      JSON.stringify({ bundleId: options.brief.bundleId, explanation: proposal.explanation, files: proposal.files }, null, 2),
    );

    // A single-project build has no module segment, so the task is just
    // ":test". Building ":<empty>:test" produces "::test", which Gradle
    // rejects, and the failure looks like a broken fix rather than a broken
    // task path.
    const module = options.module.trim().replace(/^:+|:+$/g, "");
    const testTask = module.length === 0 ? ":test" : `:${module}:test`;
    const run = await exec("./gradlew", [testTask, "--console=plain", "-q"], {
      cwd: options.sourceRoot,
      timeoutMs: 900_000,
    });

    const output = run.stdout.toString("utf8") + run.stderr;

    if (/e: file:|Compilation error|compilation failed/i.test(output)) {
      buildOk = false;
      testsOk = false;
      error = `compile failed:\n${firstLines(output, 12)}`;
    } else {
      buildOk = true;
      testsOk = run.code === 0;
      if (!testsOk) error = `tests failed:\n${firstLines(output, 20)}`;
    }
  } catch (caught) {
    error = (caught as Error).message;
  } finally {
    // Restore on any failure. A green run keeps the files in place so the
    // caller can run the replay against the fixed tree.
    const keep = applied && buildOk && testsOk;
    if (!keep) {
      for (const entry of journal) {
        await writeFile(entry.absolutePath, entry.originalContent);
      }
      if (applied) notes.push("files restored: the fix did not build or did not pass tests");
      applied = false;
    }
  }

  return {
    bundleId: options.brief.bundleId,
    model: proposal.model,
    durationMs: Date.now() - startedAt,
    filesTouched,
    applied,
    buildOk,
    testsOk,
    failureGone: null,
    appliedPatchPath,
    error,
    notes,
  };
}

/** Restores every file recorded in a journal entry set. */
export async function restoreJournal(journal: JournalEntry[]): Promise<void> {
  for (const entry of journal) {
    await writeFile(entry.absolutePath, entry.originalContent);
  }
}

export function hashContent(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}

function firstLines(value: string, count: number): string {
  return value.split("\n").filter((line) => line.trim().length > 0).slice(0, count).join("\n");
}

export async function briefFromDisk(bundleDir: string): Promise<RepairBrief> {
  const path = join(bundleDir, "repair-brief.json");
  if (!existsSync(path)) {
    throw new Error(`No repair brief at ${path}. Run: factory repair --bundle ${bundleDir}`);
  }
  return JSON.parse(await readFile(path, "utf8")) as RepairBrief;
}

export { SYSTEM_PROMPT };

// taskfix

// taskpath
