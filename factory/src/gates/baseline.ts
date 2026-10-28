import { readFile, writeFile } from "node:fs/promises";
import type { BaselineEntry, GateReport } from "./stubGate.js";

export interface StubBaselineFile {
  version: 1;
  generatedAt: string;
  note: string;
  entries: BaselineEntry[];
}

/**
 * The stub-gate baseline. Unlike a formatter baseline this one is not a way to
 * ignore debt: it exists so the gate can be turned on against a tree that
 * already has findings without blocking every build, and it must shrink.
 *
 * The note field is written into the file so a future instance reading it knows
 * the rule: an entry here is a known lie, and every entry is a task.
 */
export async function readBaseline(path: string): Promise<StubBaselineFile> {
  try {
    const raw = await readFile(path, "utf8");
    const parsed = JSON.parse(raw) as StubBaselineFile;
    if (!Array.isArray(parsed.entries)) {
      return emptyBaseline();
    }
    return parsed;
  } catch {
    return emptyBaseline();
  }
}

export function emptyBaseline(): StubBaselineFile {
  return {
    version: 1,
    generatedAt: new Date().toISOString(),
    note: "Known stub findings. Every entry is a task; this file must only shrink.",
    entries: [],
  };
}

export async function writeBaseline(path: string, report: GateReport): Promise<void> {
  const baseline: StubBaselineFile = {
    version: 1,
    generatedAt: new Date().toISOString(),
    note: "Known stub findings. Every entry is a task; this file must only shrink.",
    entries: report.findings.map((finding) => ({
      rule: finding.rule,
      file: finding.file,
      line: finding.line,
    })),
  };
  await writeFile(path, JSON.stringify(baseline, null, 2));
}

export function summarise(report: GateReport): string {
  const lines: string[] = [];
  lines.push(`Scanned ${report.scannedFiles} Kotlin files.`);
  lines.push(`Findings: ${report.findings.length} total, ${report.newFindings.length} new, ${report.baselined.length} baselined.`);

  if (report.errors.length > 0) {
    lines.push(`Errors: ${report.errors.length}`);
    for (const error of report.errors) lines.push(`  ! ${error}`);
  }

  if (report.newFindings.length > 0) {
    lines.push("");
    lines.push("NEW FINDINGS (the gate fails on these):");
    for (const finding of report.newFindings) {
      lines.push(`  ${finding.file}:${finding.line}:${finding.column} [${finding.rule}] ${finding.message}`);
      lines.push(`      ${finding.excerpt}`);
    }
  }

  if (report.baselined.length > 0) {
    lines.push("");
    lines.push(`BASELINED (known, still debt): ${report.baselined.length}`);
    const byRule = new Map<string, number>();
    for (const finding of report.baselined) {
      byRule.set(finding.rule, (byRule.get(finding.rule) ?? 0) + 1);
    }
    for (const [rule, count] of byRule) lines.push(`  ${rule}: ${count}`);
  }

  return lines.join("\n");
}
