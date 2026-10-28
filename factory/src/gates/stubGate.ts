import { readdir, readFile } from "node:fs/promises";
import { join, relative } from "node:path";

/**
 * The stub gate. Static analysis that answers one question: does this source
 * tree contain code that is pretending to be finished?
 *
 * detekt would be the usual tool. Its Gradle plugin does not load on this
 * toolchain (Kotlin 2.3.10 + AGP 9.3.3 + Gradle 9.5 throws
 * NoClassDefFoundError for KotlinJvmProjectExtension), so the gate is built
 * here instead. That is not a downgrade: this gate is ours, it has no network
 * dependency, and every rule below is one we chose rather than one we inherited.
 *
 * The rule set is deliberately narrow. A stub gate that fires on style gets
 * ignored, and an ignored gate is worse than no gate. Each rule must be a
 * genuine lie about completeness.
 */

export type StubRuleId =
  | "TODO_CALL"
  | "NOT_IMPLEMENTED"
  | "EMPTY_CATCH"
  | "STUB_MARKER"
  | "SWALLOWED_RESULT";

export interface Finding {
  rule: StubRuleId;
  file: string;
  line: number;
  column: number;
  message: string;
  excerpt: string;
}

export interface BaselineEntry {
  rule: StubRuleId;
  file: string;
  line: number;
}

export interface GateReport {
  scannedFiles: number;
  findings: Finding[];
  baselined: Finding[];
  newFindings: Finding[];
  errors: string[];
}

export interface GateOptions {
  root: string;
  /** Directories to skip entirely, relative to root. */
  excludeDirs?: string[];
  /** Baseline of known, accepted findings. Anything not listed fails. */
  baseline?: BaselineEntry[];
}

const DEFAULT_EXCLUDES = [
  "build",
  ".gradle",
  ".git",
  ".codanna",
  ".fastembed_cache",
  "node_modules",
  "generated",
];

// Kotlin source only. Java, XML and Gradle scripts have their own failure
// modes and are not what this gate exists to catch.
const SOURCE_EXTENSION = ".kt";

export async function runStubGate(options: GateOptions): Promise<GateReport> {
  const excludeDirs = new Set(options.excludeDirs ?? DEFAULT_EXCLUDES);
  const files = await collectKotlinFiles(options.root, options.root, excludeDirs);

  const findings: Finding[] = [];
  const errors: string[] = [];

  for (const file of files) {
    try {
      const content = await readFile(file, "utf8");
      findings.push(...scanSource(content, relative(options.root, file)));
    } catch (error) {
      errors.push(`${file}: ${(error as Error).message}`);
    }
  }

  const baselineKeys = new Set(
    (options.baseline ?? []).map((entry) => baselineKey(entry.rule, entry.file, entry.line)),
  );

  const baselined: Finding[] = [];
  const newFindings: Finding[] = [];

  for (const finding of findings) {
    if (baselineKeys.has(baselineKey(finding.rule, finding.file, finding.line))) {
      baselined.push(finding);
    } else {
      newFindings.push(finding);
    }
  }

  return {
    scannedFiles: files.length,
    findings,
    baselined,
    newFindings,
    errors,
  };
}

function baselineKey(rule: StubRuleId, file: string, line: number): string {
  return `${rule}|${file}|${line}`;
}

async function collectKotlinFiles(
  root: string,
  current: string,
  excludeDirs: Set<string>,
): Promise<string[]> {
  const entries = await readdir(current, { withFileTypes: true });
  const files: string[] = [];

  for (const entry of entries) {
    if (entry.isDirectory()) {
      if (excludeDirs.has(entry.name)) continue;
      files.push(...(await collectKotlinFiles(root, join(current, entry.name), excludeDirs)));
      continue;
    }
    if (entry.isFile() && entry.name.endsWith(SOURCE_EXTENSION)) {
      files.push(join(current, entry.name));
    }
  }

  return files;
}

/**
 * Scans one file's text. Line-oriented on purpose: the rules are local, and a
 * real parse would buy precision this gate does not need while costing the
 * ability to run without a Kotlin compiler on the classpath.
 */
export function scanSource(content: string, file: string): Finding[] {
  const findings: Finding[] = [];
  const lines = content.split("\n");

  // Track block comments so a TODO inside a doc comment is still caught (it is
  // still a promise nobody kept) but code inside a string is not.
  for (let index = 0; index < lines.length; index += 1) {
    const lineNumber = index + 1;
    const line = lines[index];
    const trimmed = line.trim();

    // TODO() is a Kotlin stdlib function that throws NotImplementedError at
    // runtime. Its presence in shipped code is the clearest possible stub.
    const todoCall = /\bTODO\s*\(/.exec(line);
    if (todoCall && !isInsideStringLiteral(line, todoCall.index)) {
      findings.push({
        rule: "TODO_CALL",
        file,
        line: lineNumber,
        column: todoCall.index + 1,
        message: "TODO() throws NotImplementedError at runtime. This code cannot do its job.",
        excerpt: trimmed,
      });
    }

    const notImplemented = /throw\s+NotImplementedError\s*\(/.exec(line);
    if (notImplemented) {
      findings.push({
        rule: "NOT_IMPLEMENTED",
        file,
        line: lineNumber,
        column: notImplemented.index + 1,
        message: "Throws NotImplementedError. Either implement it or report BLOCKED.",
        excerpt: trimmed,
      });
    }

    // Marker comments in shipped code. These are promises, not documentation.
    const marker = /\b(TODO|FIXME|XXX|HACK|STOPSHIP)\b\s*[:\-]?/.exec(line);
    if (marker && isCommentLine(trimmed) && !isAllowedMarker(trimmed)) {
      findings.push({
        rule: "STUB_MARKER",
        file,
        line: lineNumber,
        column: marker.index + 1,
        message: `${marker[1]} marker in source. The work was deferred, not done.`,
        excerpt: trimmed,
      });
    }

    // Empty catch: a failure path that silently continues.
    const emptyCatch = /catch\s*\([^)]*\)\s*\{\s*\}/.exec(line);
    if (emptyCatch) {
      findings.push({
        rule: "EMPTY_CATCH",
        file,
        line: lineNumber,
        column: emptyCatch.index + 1,
        message: "Empty catch block. The error is being swallowed.",
        excerpt: trimmed,
      });
      continue;
    }

    // Multi-line empty catch: `catch (e: X) {` then `}` with only whitespace
    // or a comment between.
    const openCatch = /catch\s*\([^)]*\)\s*\{\s*$/.exec(line);
    if (openCatch) {
      const next = findNextCodeLine(lines, index + 1);
      if (next !== null && lines[next].trim() === "}") {
        findings.push({
          rule: "EMPTY_CATCH",
          file,
          line: lineNumber,
          column: openCatch.index + 1,
          message: "Empty catch block. The error is being swallowed.",
          excerpt: trimmed,
        });
      }
    }

    // A caught exception that is never referenced in the immediately following
    // block is swallowed even when the block is not empty.
    const namedCatch = /catch\s*\(\s*(\w+|_)\s*:\s*[^)]+\)\s*\{/.exec(line);
    if (namedCatch) {
      const name = namedCatch[1];
      if (name !== "_" && !isExemptCatchName(name)) {
        const blockText = readCatchBlock(lines, index);
        if (blockText !== null && !blockMentions(blockText, name) && !blockRethrows(blockText)) {
          findings.push({
            rule: "SWALLOWED_RESULT",
            file,
            line: lineNumber,
            column: namedCatch.index + 1,
            message: `Caught "${name}" is never read and never rethrown. The failure is lost.`,
            excerpt: trimmed,
          });
        }
      }
    }

    // runCatching { ... }.getOrNull() discards the failure just as completely.
    const runCatchingNull = /runCatching\s*\{[^}]*\}\s*\.getOrNull\s*\(\s*\)/.exec(line);
    if (runCatchingNull) {
      findings.push({
        rule: "SWALLOWED_RESULT",
        file,
        line: lineNumber,
        column: runCatchingNull.index + 1,
        message: "runCatching().getOrNull() discards the failure. Log it or handle it.",
        excerpt: trimmed,
      });
    }
  }

  return findings;
}

function findNextCodeLine(lines: string[], start: number): number | null {
  for (let index = start; index < lines.length; index += 1) {
    const trimmed = lines[index].trim();
    if (trimmed.length === 0) continue;
    if (isCommentLine(trimmed)) continue;
    return index;
  }
  return null;
}

function readCatchBlock(lines: string[], openLineIndex: number): string | null {
  const collected: string[] = [];
  let depth = 0;
  let started = false;
  // The catch header itself contains the parameter name, so including it would
  // always count as one "use" and hide every swallowed exception. Start
  // collecting only after the opening brace on the header line.
  let seenOpeningBrace = false;

  for (let index = openLineIndex; index < lines.length; index += 1) {
    const line = lines[index];
    let remainder = line;

    if (index === openLineIndex) {
      const braceAt = line.indexOf("{");
      if (braceAt === -1) return null;
      remainder = line.slice(braceAt);
    }

    let segmentStart = 0;
    for (let position = 0; position < remainder.length; position += 1) {
      const character = remainder[position];
      if (character === "{") {
        depth += 1;
        if (!seenOpeningBrace) {
          seenOpeningBrace = true;
          segmentStart = position + 1;
        }
      } else if (character === "}") {
        depth -= 1;
        if (started || seenOpeningBrace) {
          if (depth === 0 && seenOpeningBrace) {
            const body = remainder.slice(segmentStart, position);
            if (body.length > 0) collected.push(body);
            return collected.join("\n");
          }
        }
      }
    }

    started = seenOpeningBrace;
    if (seenOpeningBrace) {
      const body = remainder.slice(segmentStart);
      if (body.length > 0) collected.push(body);
    }
  }
  return null;
}

function blockMentions(blockText: string, name: string): boolean {
  // Count references in the block BODY only. The declaration in the catch
  // header is not a use; only reading, logging, wrapping, rethrowing or
  // inspecting the caught value counts.
  const pattern = new RegExp(`\\b${escapeRegExp(name)}\\b`);
  return pattern.test(blockText);
}

function blockRethrows(blockText: string): boolean {
  return /\bthrow\b/.test(blockText);
}

function isExemptCatchName(name: string): boolean {
  return /^(_|ignore|ignored|expected|ok)$/i.test(name);
}

function isAllowedMarker(trimmed: string): boolean {
  // A marker that names a real, tracked issue is still a promise, but it is a
  // promise with a handle on it. Keep the rule strict: only an explicit
  // "seen:" annotation exempts a line, and only because the gate's own
  // baseline cannot see comments.
  return trimmed.includes("stub-gate: allow");
}

function isCommentLine(trimmed: string): boolean {
  return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
}

function isInsideStringLiteral(line: string, index: number): boolean {
  let inString = false;
  for (let position = 0; position < index; position += 1) {
    const character = line[position];
    if (character === '"' && line[position - 1] !== "\\") inString = !inString;
  }
  return inString;
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
