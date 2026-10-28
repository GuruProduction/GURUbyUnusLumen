import { readFile, writeFile, readdir, mkdir, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { createHash } from "node:crypto";
import { join } from "node:path";
import { exec } from "../lib/exec.js";

/**
 * Source-level mutation testing.
 *
 * PIT is the usual tool. Its Gradle plugin calls `baseDir` on
 * ReportingExtension, an API Gradle 9 removed, so it cannot load here. Rather
 * than fight the toolchain, this mutator does the same job the honest way:
 *
 *   1. take a real source file with passing tests
 *   2. make one small semantic change that a test should notice
 *   3. run that module's real test suite
 *   4. if the tests still pass, the mutant survived: the tests have a hole
 *   5. put the file back, always
 *
 * A surviving mutant is the most honest signal a test suite can produce. It
 * says: this line of behaviour is not covered by anything.
 *
 * SAFETY: mutating files on disk is dangerous. Before any write the original
 * bytes are recorded in a recovery journal. If the process dies mid-run, the
 * next invocation of this tool restores every journalled file before doing
 * anything else. Every mutation is also restored in a finally block and
 * verified by hash.
 */

export type MutantRule =
  | "EQUALITY_FLIP"
  | "BOUNDARY_SHIFT"
  | "BOOLEAN_RETURN_FLIP"
  | "LOGICAL_OP_FLIP";

export type MutantOutcome = "killed" | "survived" | "compile_error" | "timeout";

export interface Mutant {
  id: string;
  file: string;
  line: number;
  rule: MutantRule;
  original: string;
  mutated: string;
  description: string;
}

export interface MutantResult extends Mutant {
  outcome: MutantOutcome;
  durationMs: number;
  detail: string;
}

export interface ModuleMutationReport {
  module: string;
  testTask: string;
  mutantsPlanned: number;
  mutantsRun: number;
  killed: number;
  survived: number;
  compileErrors: number;
  timeouts: number;
  score: number | null;
  results: MutantResult[];
  survivors: MutantResult[];
}

export interface MutationOptions {
  root: string;
  module: string;
  maxMutants?: number;
  timeoutMs?: number;
  /** Only mutate these files, relative to the module root. */
  files?: string[];
  /** Skip mutants whose line matches this regex (generated code, etc). */
  skipLinePattern?: RegExp;
}

interface JournalEntry {
  absolutePath: string;
  originalContent: string;
  startedAt: string;
}

const JOURNAL_PATH_SEGMENT = ".mutation-journal.json";

export function journalPath(factoryRoot: string): string {
  return join(factoryRoot, JOURNAL_PATH_SEGMENT);
}

/**
 * Restores any file left mutated by a previous crashed run. Must be called
 * before planning a new run.
 */
export async function recoverFromJournal(factoryRoot: string): Promise<string[]> {
  const path = journalPath(factoryRoot);
  if (!existsSync(path)) return [];

  const entries = JSON.parse(await readFile(path, "utf8")) as JournalEntry[];
  const restored: string[] = [];

  for (const entry of entries) {
    if (existsSync(entry.absolutePath)) {
      await writeFile(entry.absolutePath, entry.originalContent);
      restored.push(entry.absolutePath);
    }
  }

  await rm(path, { force: true });
  return restored;
}

async function writeJournal(factoryRoot: string, entries: JournalEntry[]): Promise<void> {
  const path = journalPath(factoryRoot);
  if (entries.length === 0) {
    await rm(path, { force: true });
    return;
  }
  await mkdir(join(factoryRoot), { recursive: true });
  await writeFile(path, JSON.stringify(entries, null, 2));
}

/** The mutation rules. Deliberately semantic, never stylistic. */
const RULES: { rule: MutantRule; description: string; apply: (line: string) => string | null }[] = [
  {
    rule: "EQUALITY_FLIP",
    description: "flip == to !=",
    apply: (line) => flipToken(line, "==", "!="),
  },
  {
    rule: "BOUNDARY_SHIFT",
    description: "shift a comparison boundary",
    apply: (line) => {
      const variants: [string, string][] = [
        [">=", ">"],
        ["<=", "<"],
        [">", ">="],
        ["<", "<="],
      ];
      for (const [from, to] of variants) {
        const mutated = shiftComparison(line, from, to);
        if (mutated !== null) return mutated;
      }
      return null;
    },
  },
  {
    rule: "BOOLEAN_RETURN_FLIP",
    description: "flip a boolean return",
    apply: (line) => {
      // Two shapes matter in Kotlin: a block-body return and an expression-body
      // function (`fun isReady(): Boolean = true`). Both are equally real code
      // paths, so both get flipped.
      if (/\breturn\s+true\b/.test(line)) return line.replace(/\breturn\s+true\b/, "return false");
      if (/\breturn\s+false\b/.test(line)) return line.replace(/\breturn\s+false\b/, "return true");

      const expressionTrue = /=\s*true\s*$/.exec(line);
      if (expressionTrue) {
        return line.slice(0, expressionTrue.index) + "= false";
      }
      const expressionFalse = /=\s*false\s*$/.exec(line);
      if (expressionFalse) {
        return line.slice(0, expressionFalse.index) + "= true";
      }

      return null;
    },
  },
  {
    rule: "LOGICAL_OP_FLIP",
    description: "flip && to ||",
    apply: (line) => {
      if (line.includes("&&")) return line.replace("&&", "||").includes("||") ? swapLogical(line) : null;
      if (line.includes("||")) return swapLogical(line);
      return null;
    },
  },
];

function swapLogical(line: string): string | null {
  if (line.includes("&&")) return line.replace("&&", "||");
  if (line.includes("||")) return line.replace("||", "&&");
  return null;
}

function flipToken(line: string, from: string, to: string): string | null {
  // Never touch === or !==. Those are identity comparisons and flipping them
  // changes semantics in a way tests rarely assert on deliberately.
  const guard = new RegExp(`(^|[^=!])${escapeRegExp(from)}(?!\\=)`);
  if (guard.test(line)) {
    return line.replace(guard, (match, prefix: string) => `${prefix}${to}`);
  }
  const reverse = new RegExp(`(^|[^=!])${escapeRegExp(to)}(?!\\=)`);
  if (reverse.test(line)) {
    return line.replace(reverse, (match, prefix: string) => `${prefix}${from}`);
  }
  return null;
}

function shiftComparison(line: string, from: string, to: string): string | null {
  // Only shift a comparison that stands alone as an operator. This has to be
  // strict: a lambda arrow `->`, a generic type parameter `<T>` and an
  // expression body `=` all contain the same characters, and mutating any of
  // them produces code that cannot compile, which is a useless mutant.
  const index = line.indexOf(from);
  if (index === -1) return null;

  const before = index > 0 ? line[index - 1] : "";
  const after = index + from.length < line.length ? line[index + from.length] : "";

  // `->` (lambda arrow) or `=>`: the previous character is the join.
  if (before === "-" || before === "=") return null;
  // `=` immediately before means this is part of `>=`, `<=`, `==` handled
  // elsewhere; do not double-mutate an operator.
  if (from === ">" && before === "=") return null;
  if (from === "<" && before === "=") return null;
  // Generic type parameter: `List<Int>`, `<T>`. Require a space or a closing
  // paren/bracket/identifier boundary that looks like an expression, not a type.
  if ((from === "<" || from === ">") && /[A-Za-z0-9_>]/.test(after)) return null;
  if ((from === "<" || from === ">") && /[A-Za-z0-9_<]/.test(before)) return null;

  // A comparison operator should have whitespace or a bracket/paren on at
  // least one side. `a>b` is legal Kotlin but rare; refusing it is safer than
  // mangling a type.
  const hasSpaceBefore = before === "" || /\s/.test(before) || /[)\]]/.test(before);
  const hasSpaceAfter = after === "" || /\s/.test(after) || /[(\[]/.test(after);
  if (!hasSpaceBefore && !hasSpaceAfter) return null;

  return line.slice(0, index) + to + line.slice(index + from.length);
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** Plans mutants without touching disk. */
export function planMutants(
  fileContents: Map<string, string>,
  options: { maxMutants: number; skipLinePattern?: RegExp },
): Mutant[] {
  const mutants: Mutant[] = [];

  for (const [file, content] of fileContents) {
    const lines = content.split("\n");

    for (let index = 0; index < lines.length; index += 1) {
      if (mutants.length >= options.maxMutants) return mutants;
      const line = lines[index];
      const trimmed = line.trim();

      if (line.length > 400) continue;
      if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
      if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) continue;
      if (options.skipLinePattern?.test(line)) continue;

      // Data-class defaults and property initialisers are values supplied by
      // a constructor, not branching logic. Flipping `val included: Boolean =
      // true` produces no observable behaviour change for any test that never
      // constructs the object with the other value, so reporting it as a
      // surviving mutant is noise that hides real survivors.
      if (/^\s*(val|var)\s+\w+\s*:\s*\w+\s*=\s*(true|false)\s*,?\s*$/.test(line)) continue;

      // Data-class defaults and property initialisers are values supplied by
      // a constructor, not branching logic. Flipping `val included: Boolean =
      // true` produces no observable behaviour change for any test that never
      // constructs the object with the other value, so reporting it as a
      // surviving mutant is noise that hides real survivors.
      if (/^\s*(val|var)\s+\w+\s*:\s*\w+\s*=\s*(true|false)\s*,?\s*$/.test(line)) continue;

      for (const rule of RULES) {
        const mutated = rule.apply(line);
        if (mutated === null || mutated === line) continue;

        mutants.push({
          id: `${file}:${index + 1}:${rule.rule}`,
          file,
          line: index + 1,
          rule: rule.rule,
          original: line,
          mutated,
          description: rule.description,
        });
        break; // one mutation per line keeps the signal attributable
      }
    }
  }

  return mutants;
}

async function collectSources(moduleRoot: string): Promise<Map<string, string>> {
  const files = new Map<string, string>();
  const base = join(moduleRoot, "src", "main");
  if (!existsSync(base)) return files;

  async function walk(current: string): Promise<void> {
    const entries = await readdir(current, { withFileTypes: true });
    for (const entry of entries) {
      const full = join(current, entry.name);
      if (entry.isDirectory()) {
        if (entry.name === "build" || entry.name === "generated") continue;
        await walk(full);
        continue;
      }
      if (entry.isFile() && entry.name.endsWith(".kt")) {
        files.set(full, await readFile(full, "utf8"));
      }
    }
  }

  await walk(base);
  return files;
}

export async function runModuleMutation(
  options: MutationOptions,
  factoryRoot: string,
): Promise<ModuleMutationReport> {
  // The module is named the Gradle way (`calendar:domain`) but its directory is
  // `calendar/domain`. Joining the colon form produces a path that does not
  // exist, which silently planned zero mutants and looked like a clean result.
  const modulePath = options.module.replace(/:/g, "/");
  const moduleRoot = join(options.root, modulePath);
  const gradleRoot = options.root;
  const testTask = `:${options.module}:test`;
  const maxMutants = options.maxMutants ?? 12;
  const timeoutMs = options.timeoutMs ?? 240_000;

  const sources = await collectSources(moduleRoot);
  const filtered = options.files
    ? new Map([...sources].filter(([path]) => options.files?.some((f) => path.endsWith(f))))
    : sources;

  const planned = planMutants(filtered, {
    maxMutants,
    skipLinePattern: options.skipLinePattern,
  });

  const results: MutantResult[] = [];
  const journal: JournalEntry[] = [];

  try {
    for (const mutant of planned) {
      const absolutePath = join(moduleRoot, "src", "main", mutant.file.replace(/^.*?src\/main\//, ""));
      const path = mutant.file;
      const originalContent = sources.get(path);
      if (originalContent === undefined) continue;

      const mutatedContent = originalContent
        .split("\n")
        .map((line, index) => (index + 1 === mutant.line ? mutant.mutated : line))
        .join("\n");

      journal.push({
        absolutePath,
        originalContent,
        startedAt: new Date().toISOString(),
      });
      await writeJournal(factoryRoot, journal);

      const startedAt = Date.now();
      let outcome: MutantOutcome = "survived";
      let detail = "";

      try {
        await writeFile(absolutePath, mutatedContent);
        const run = await exec(
          "./gradlew",
          [testTask, "--console=plain", "-q"],
          { cwd: gradleRoot, timeoutMs },
        );

        const output = run.stdout.toString("utf8") + run.stderr;

        if (run.code === 0) {
          outcome = "survived";
          detail = "tests passed with the mutation in place";
        } else if (/e: file:|Compilation error|compilation failed/i.test(output)) {
          outcome = "compile_error";
          detail = "mutation did not compile";
        } else {
          outcome = "killed";
          detail = firstFailingTest(output) ?? "test suite failed";
        }
      } catch (error) {
        const message = (error as Error).message;
        if (/timed out/i.test(message)) {
          outcome = "timeout";
          detail = "test run exceeded the timeout";
        } else {
          outcome = "compile_error";
          detail = message.slice(0, 200);
        }
      } finally {
        await writeFile(absolutePath, originalContent);
        const restored = await readFile(absolutePath, "utf8");
        if (sha(restored) !== sha(originalContent)) {
          throw new Error(`Failed to restore ${absolutePath}; halting before further mutation`);
        }
        journal.pop();
        await writeJournal(factoryRoot, journal);
      }

      results.push({
        ...mutant,
        outcome,
        durationMs: Date.now() - startedAt,
        detail,
      });
    }
  } finally {
    // Belt and braces: restore anything still journalled.
    for (const entry of journal) {
      await writeFile(entry.absolutePath, entry.originalContent);
    }
    await writeJournal(factoryRoot, []);
  }

  const killed = results.filter((r) => r.outcome === "killed").length;
  const survived = results.filter((r) => r.outcome === "survived").length;
  const compileErrors = results.filter((r) => r.outcome === "compile_error").length;
  const timeouts = results.filter((r) => r.outcome === "timeout").length;
  const denominator = killed + survived;

  return {
    module: options.module,
    testTask,
    mutantsPlanned: planned.length,
    mutantsRun: results.length,
    killed,
    survived,
    compileErrors,
    timeouts,
    score: denominator === 0 ? null : Math.round((killed / denominator) * 1000) / 10,
    results,
    survivors: results.filter((r) => r.outcome === "survived"),
  };
}

function firstFailingTest(output: string): string | null {
  const match = /([A-Za-z0-9_.]+Test)\s*>\s*(.+?)\s+FAILED/.exec(output);
  if (!match) return null;
  return `${match[1]} > ${match[2]}`;
}

function sha(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}
