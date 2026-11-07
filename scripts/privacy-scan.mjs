#!/usr/bin/env node
// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.

/**
 * THE PRIVACY GATE.
 *
 * This refuses a commit that would publish something the owner has to keep to himself: a
 * home or public IP address, a credential, a private key, a token, or a path that names the
 * machine it was written on.
 *
 * WHY IT EXISTS AT ALL. The dev loop has required a privacy gate since it was written, and
 * the script it named was never on any machine, so the gate has never once run. The owner's
 * home addresses have been published to a public repository four times. A rule that is not
 * executable is not a rule.
 *
 * WHAT IT SCANS. Either the staged diff (the default, for the pre-commit hook) or a set of
 * whole files (`--path`), which is what the release-note and PR-body checks need, because
 * prose is where an address gets pasted by hand.
 *
 * WHAT IT DOES NOT SCAN. Binaries. A compiled object's bytes are not text and every pattern
 * here would either miss the point or scream at compressed data. The exception is a
 * credential pattern in a text-like file, which is exactly where credentials live.
 *
 * THE TWO FAILURE MODES, AND WHICH ONE THIS CHOOSES. A gate that misses a real address costs
 * the owner his privacy. A gate that flags a documentation address costs a developer ten
 * seconds. So the rule is: every address that is not provably documentation is refused, and
 * the allowlist below is the only place a documented exception may be written. There is no
 * "probably fine".
 *
 * EXIT CODES
 *   0  clean
 *   1  a finding, which must be fixed rather than argued with
 *   2  the scanner could not do its job, which is also a failure
 */

import { execFileSync } from "node:child_process";
import { readFileSync, statSync } from "node:fs";
import { relative } from "node:path";

// ---------------------------------------------------------------------------
// The allowlist. Documentation ranges and loopback only, per RFC 5737, RFC 1918's
// own documentation convention, and the two addresses every codebase uses.
// ---------------------------------------------------------------------------

/** Whole literals that are always safe, whatever they look like. */
const SAFE_LITERALS = new Set([
  "127.0.0.1",
  "0.0.0.0",
  "255.255.255.255",
  "localhost",
  "::1",
  "127.0.0.1:11434",
]);

/** IPv4 documentation ranges: these three can never be a real host. */
const DOC_RANGES = [
  { label: "TEST-NET-1", first: [192, 0, 2] },
  { label: "TEST-NET-2", first: [198, 51, 100] },
  { label: "TEST-NET-3", first: [203, 0, 113] },
];

/**
 * A MAC address whose octets spell something is a placeholder. `aa:bb:cc:dd:ee:ff` is the
 * textbook one; `de:ad:be:ef:00:00` is the other. Both are so obviously invented that
 * refusing them would train a developer to bypass the gate rather than fix the value.
 */
const PLACEHOLDER_MAC_TAILS = new Set([
  "aa:bb:cc",
  "11:22:33",
  "00:11:22",
  "de:ad:be",
  "ef:00:00",
  "12:34:56",
  "ab:cd:ef",
  "01:02:03",
  "dd:ee:ff",
  "ee:ff:00",
  "00:00:00",
]);

// ---------------------------------------------------------------------------
// Patterns
// ---------------------------------------------------------------------------

/**
 * A dotted quad. Deliberately permissive: four groups of 1-3 digits with no range check,
 * because out-of-range octets in a "wrong" address are exactly the sort of value a human
 * pastes and a strict matcher then skips.
 */
const IPV4 = /\b(?:\d{1,3}\.){3}\d{1,3}\b/g;

/** An IPv6 address, in the forms people actually write. */
const IPV6 = /\b(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{0,4}\b/g;

/** MAC addresses with colons or dashes. */
const MAC = /\b(?:[0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}\b/g;

/**
 * Private key blocks. A single line anywhere in any file is enough: these never belong in a
 * repository and there is no context in which one is a false positive.
 *
 * ASSEMBLED FROM PARTS ON PURPOSE. This file is scanned by this file, so a literal PEM header
 * written here would be refused by the rule it defines, and the only ways out would be to
 * allowlist the scanner (which would let a real key hide in it) or to stop checking it at all.
 * Building the pattern at runtime means the source carries no PEM header while the compiled
 * expression still matches one.
 */
const PEM_HEADER = `-----BEGIN ${"(?:RSA |EC |DSA |OPENSSH |PGP |ENCRYPTED )?"}PRIVATE KEY`;
const PRIVATE_KEY_BLOCK = new RegExp(`${PEM_HEADER}(?:\\s+BLOCK)?-----`);

/**
 * Credential assignments. The shape is a name that says what it is, then a separator, then a
 * value long enough and random enough to be a real secret rather than a word.
 *
 * WHY THE NAME MATTERS. `password = "your password"` is documentation and
 * `password = "hunter2"` is a joke, but both are noise. `apiKey = "sk-live-..."` is the thing
 * that costs money. Requiring a credential-shaped name keeps the signal.
 */
const CREDENTIAL_NAMES =
  "(?:api[_-]?key|apikey|secret|secret[_-]?key|token|access[_-]?token|auth[_-]?token|" +
  "bearer|password|passwd|private[_-]?key|client[_-]?secret|session[_-]?id|" +
  "aws[_-]?secret[_-]?access[_-]?key|signing[_-]?key|keystore[_-]?password)";

const CREDENTIAL_ASSIGNMENT = new RegExp(
  `\\b${CREDENTIAL_NAMES}\\b\\s*[:=]\\s*["'\`]([^"'\`\\s]{12,})["'\`]`,
  "gi",
);

/** Known credential shapes, which need no variable name to be recognisable. */
const KNOWN_TOKENS = [
  { label: "AWS access key id", re: /\b(?:AKIA|ASIA)[0-9A-Z]{16}\b/g },
  { label: "GitHub token", re: /\bgh[pousr]_[A-Za-z0-9]{36,}\b/g },
  { label: "GitHub fine-grained token", re: /\bgithub_pat_[A-Za-z0-9_]{60,}\b/g },
  { label: "Anthropic key", re: /\bsk-ant-[A-Za-z0-9_-]{20,}\b/g },
  { label: "OpenAI key", re: /\bsk-(?:proj-)?[A-Za-z0-9]{32,}\b/g },
  { label: "Google API key", re: /\bAIza[0-9A-Za-z_-]{35}\b/g },
  { label: "xAI key", re: /\bxai-[A-Za-z0-9]{40,}\b/g },
  { label: "Stripe live key", re: /\b(?:sk|rk)_live_[A-Za-z0-9]{20,}\b/g },
  { label: "Slack token", re: /\bxox[abprs]-[A-Za-z0-9-]{10,}\b/g },
  { label: "Telegram bot token", re: /\b\d{8,10}:[A-Za-z0-9_-]{35}\b/g },
  { label: "JWT", re: /\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/g },
];

// A PGP private key needs no separate rule: the generic private-key rule above already
// matches its header through the PGP alternative. It used to be listed twice, and the second
// entry only ever produced duplicate findings for one key.

/**
 * Machine paths. A path that names the owner's Mac, his home directory or a worktree is a
 * fingerprint of his machine, and it has shipped in release notes before.
 */
const MACHINE_PATHS = [
  { label: "owner home directory", re: /\/Users\/(?!shared\/|runner\/)[A-Za-z0-9._-]+/g },
  { label: "Homebrew path", re: /\/opt\/homebrew\//g },
  { label: "worktree path", re: /\/guru-worktrees\//g },
];

/**
 * Files whose whole existence is a secret. These are refused by name before any content is
 * read, because the correct answer is that they never enter the repository at all.
 */
const FORBIDDEN_PATHS = [
  { label: "private key", re: /(?:^|\/)(?:id_[rd]sa|id_ecdsa|id_ed25519)(?:\.pub)?$/ },
  { label: "keystore", re: /\.(?:jks|keystore|bks|p12|pfx)$/i },
  { label: "environment file", re: /(?:^|\/)\.env(?:\.|$)/ },
  { label: "credential file", re: /(?:^|\/)(?:credentials|secrets)\.(?:json|ya?ml|xml|properties)$/i },
  { label: "service account", re: /(?:^|\/)service[_-]account[^/]*\.json$/i },
  { label: "local properties", re: /(?:^|\/)local\.properties$/ },
];

// ---------------------------------------------------------------------------
// Classification
// ---------------------------------------------------------------------------

/** True when the four octets are in one of the documentation ranges. */
function isDocumentationAddress(address) {
  const parts = address.split(".").map((n) => Number.parseInt(n, 10));
  if (parts.length !== 4 || parts.some((n) => !Number.isFinite(n))) return false;
  return DOC_RANGES.some(({ first }) =>
    parts[0] === first[0] && parts[1] === first[1] && parts[2] === first[2],
  );
}

/**
 * True when the last three octets of a MAC are a placeholder tail.
 *
 * The set holds TAILS, so the comparison is on the last three octets. `d8:31:34:aa:bb:cc` is a
 * placeholder because its tail is `aa:bb:cc`; the vendor prefix in front of it is irrelevant
 * and a real prefix with an invented tail is still an invented address.
 */
function isPlaceholderMac(mac) {
  const normalised = mac.toLowerCase().replace(/-/g, ":");
  return PLACEHOLDER_MAC_TAILS.has(normalised.split(":").slice(3).join(":"));
}

/**
 * True when an IPv4 hit is really a version string, an SNMP object identifier continuation, or
 * a four-part number that is not an address at all.
 *
 * WHY THIS IS NARROW. The temptation is to allowlist anything with four dotted numbers, and
 * that is how a real address ships. So only two proven shapes are excused, both of which are
 * structurally impossible to be an address in context:
 *
 *  1. A dotted-quad that is the tail of a longer dotted-number run, which is an SNMP OID such
 *     as 1.3.6.1.2.1.1.5.0. Matching a sub-run of it as an address is a false positive by
 *     construction, because an OID's components are not an address.
 *  2. A dotted quad directly preceded by "version", "v" or "build", which is a version.
 */
function isStructuralFalsePositive(line, match, index) {
  const before = line.slice(Math.max(0, index - 40), index);
  const after = line.slice(index + match.length, index + match.length + 40);

  // Rule 1a: the run continues to the right, so this quad is a fragment of a longer number.
  if (/^\.\d{1,3}/.test(after)) return true;

  // Rule 1b: the run continues to the left, same reason.
  if (/(?:\d{1,3}\.){4,}$/.test(before)) return true;

  // Rule 2: explicitly a version rather than a host.
  if (/(?:version|ver\.?|v|build)\s*$/i.test(before)) return true;

  return false;
}

// ---------------------------------------------------------------------------
// Scanning
// ---------------------------------------------------------------------------

/** Extensions that are never text, so content patterns would only produce noise. */
const BINARY_EXTENSIONS = new Set([
  ".apk", ".aab", ".jar", ".aar", ".class", ".dex", ".so", ".dylib", ".dll", ".exe",
  ".zip", ".gz", ".tgz", ".bz2", ".xz", ".7z", ".tar", ".rar",
  ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico", ".svgz", ".heic",
  ".mp3", ".mp4", ".m4a", ".wav", ".ogg", ".opus", ".flac", ".webm", ".mov", ".avi", ".mkv",
  ".ttf", ".otf", ".woff", ".woff2", ".eot",
  ".pdf", ".docx", ".xlsx", ".pptx", ".doc", ".xls", ".ppt",
  ".bin", ".dat", ".pack", ".idx", ".realm", ".sqlite", ".db", ".keystore",
]);

function isBinaryPath(path) {
  const dot = path.lastIndexOf(".");
  if (dot < 0) return false;
  return BINARY_EXTENSIONS.has(path.slice(dot).toLowerCase());
}

/** The one-indexed line number of a character offset. */
function lineOf(content, index) {
  let line = 1;
  for (let i = 0; i < index && i < content.length; i += 1) {
    if (content.charCodeAt(i) === 10) line += 1;
  }
  return line;
}

/** A short, safe excerpt of the offending line. Never the whole secret. */
function excerptOf(content, index) {
  const start = content.lastIndexOf("\n", index) + 1;
  const end = content.indexOf("\n", index);
  const line = content.slice(start, end === -1 ? content.length : end);
  const trimmed = line.trim();
  if (trimmed.length <= 100) return trimmed;
  return `${trimmed.slice(0, 97)}...`;
}

/**
 * Scan one file's text.
 *
 * Returns findings, each naming the file, the line, the rule and a redacted excerpt. The
 * excerpt is redacted because printing the secret to a terminal is the same mistake as
 * committing it: a terminal scrollback and a CI log are both public artefacts.
 */
function scanText(path, content) {
  const findings = [];

  const add = (label, index, detail) => {
    findings.push({
      path,
      line: lineOf(content, index),
      rule: label,
      excerpt: detail ?? excerptOf(content, index),
    });
  };

  // 1. Private key blocks, anywhere, no exceptions.
  const keyMatch = PRIVATE_KEY_BLOCK.exec(content);
  if (keyMatch) add("private key block", keyMatch.index);

  // 2. Known credential shapes.
  for (const { label, re } of KNOWN_TOKENS) {
    re.lastIndex = 0;
    let m;
    while ((m = re.exec(content)) !== null) {
      add(label, m.index, `${label} detected (value redacted)`);
    }
  }

  // 3. Credential assignments.
  CREDENTIAL_ASSIGNMENT.lastIndex = 0;
  let cred;
  while ((cred = CREDENTIAL_ASSIGNMENT.exec(content)) !== null) {
    const value = cred[1];
    // A placeholder is a value that spells out what it is. Keep this list tiny and obvious.
    const looksPlaceholder =
      /^(?:x{4,}|\*{4,}|\.{3,}|your|paste|insert|changeme|placeholder|example|redacted)/i.test(
        value,
      ) || /^<.*>$/.test(value) || /^\$\{.*\}$/.test(value) || /^%[A-Z_]+%$/.test(value);
    if (looksPlaceholder) continue;
    add("credential value", cred.index, `${cred[0].split(/[:=]/)[0].trim()} has a value (redacted)`);
  }

  // 4. IPv4.
  IPV4.lastIndex = 0;
  let ip;
  while ((ip = IPV4.exec(content)) !== null) {
    const address = ip[0];
    if (SAFE_LITERALS.has(address)) continue;
    if (isDocumentationAddress(address)) continue;
    if (isStructuralFalsePositive(content, address, ip.index)) continue;
    add("IP address", ip.index, `${address} is not a documentation address`);
  }

  // 5. IPv6, only the forms that cannot be a colon-separated time or a CSS colour.
  //
  // A MAC ADDRESS IS NOT AN IPv6 ADDRESS, AND THIS PATTERN CANNOT TELL. `AA:BB:CC:DD:EE:FF`
  // matches the generic six-group form, so without the skip below every hardware address is
  // reported twice under two names, which is noise on top of noise. The MAC rule owns that
  // shape and this rule stays out of its way.
  IPV6.lastIndex = 0;
  let v6;
  while ((v6 = IPV6.exec(content)) !== null) {
    const address = v6[0];
    if (address.includes(".")) continue;
    const groups = address.split(":");
    if (groups.every((g) => g.length === 2)) continue;
    if (SAFE_LITERALS.has(address)) continue;
    if (address.startsWith("::ffff:127.") || address.startsWith("fe80:") || address === "::") continue;
    // A hexadecimal colour, an OID or a time has no double colon and no 4-group run.
    if (!address.includes("::") && groups.length < 6) continue;
    add("IPv6 address", v6.index, `${address} is not a documentation address`);
  }

  // 6. MAC addresses, placeholders excused.
  MAC.lastIndex = 0;
  let mac;
  while ((mac = MAC.exec(content)) !== null) {
    if (isPlaceholderMac(mac[0])) continue;
    add("MAC address", mac.index, `${mac[0]} looks like real hardware`);
  }

  // 7. Machine paths.
  for (const { label, re } of MACHINE_PATHS) {
    re.lastIndex = 0;
    let m;
    while ((m = re.exec(content)) !== null) {
      add(label, m.index, `${m[0]} names a machine`);
    }
  }

  return findings;
}

/** Refuse a file by name, whatever its contents. */
function forbiddenPathFinding(path) {
  for (const { label, re } of FORBIDDEN_PATHS) {
    if (re.test(path)) {
      return { path, line: 0, rule: `forbidden file (${label})`, excerpt: path };
    }
  }
  return null;
}

/** Scan one file by path. */
function scanFile(path) {
  const findings = [];
  const byPath = forbiddenPathFinding(path);
  if (byPath) findings.push(byPath);
  if (isBinaryPath(path)) return findings;
  let content;
  try {
    content = readFileSync(path, "utf8");
  } catch {
    return findings;
  }
  findings.push(...scanText(path, content));
  return findings;
}

// ---------------------------------------------------------------------------
// Entry points
// ---------------------------------------------------------------------------

/** The staged diff: every added line, with its path and line number. */
function stagedFindings() {
  const findings = [];

  const names = execFileSync(
    "git",
    ["diff", "--cached", "--name-only", "--diff-filter=ACMR"],
    { encoding: "utf8" },
  )
    .split("\n")
    .map((s) => s.trim())
    .filter(Boolean);

  for (const path of names) {
    const byPath = forbiddenPathFinding(path);
    if (byPath) {
      findings.push(byPath);
      continue;
    }
    if (isBinaryPath(path)) continue;

    let diff;
    try {
      diff = execFileSync(
        "git",
        ["diff", "--cached", "--unified=0", "--", path],
        { encoding: "utf8" },
      );
    } catch {
      continue;
    }

    let lineNumber = 0;
    for (const raw of diff.split("\n")) {
      const hunk = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(raw);
      if (hunk) {
        lineNumber = Number.parseInt(hunk[1], 10);
        continue;
      }
      if (raw.startsWith("+") && !raw.startsWith("+++")) {
        const added = raw.slice(1);
        for (const finding of scanText(path, added)) {
          findings.push({ ...finding, line: lineNumber });
        }
        lineNumber += 1;
      }
    }
  }

  return findings;
}

/** Whole files, for PR bodies, release notes and any prose about to be published. */
function pathFindings(paths) {
  const findings = [];
  for (const path of paths) {
    let isDir = false;
    try {
      isDir = statSync(path).isDirectory();
    } catch {
      findings.push({ path, line: 0, rule: "scanner error", excerpt: "file does not exist" });
      continue;
    }
    if (isDir) {
      const listed = execFileSync("git", ["ls-files", "--", path], { encoding: "utf8" })
        .split("\n")
        .map((s) => s.trim())
        .filter(Boolean);
      for (const file of listed) findings.push(...scanFile(file));
    } else {
      findings.push(...scanFile(path));
    }
  }
  return findings;
}

function report(findings, scope) {
  if (findings.length === 0) {
    process.stdout.write(`privacy gate: clean (${scope})\n`);
    return 0;
  }

  const byRule = new Map();
  for (const f of findings) {
    byRule.set(f.rule, (byRule.get(f.rule) ?? 0) + 1);
  }

  process.stderr.write(
    `\nprivacy gate: REFUSED (${scope})\n` +
      `\n${findings.length} finding(s) that must not be published:\n\n`,
  );
  const shown = findings.slice(0, 60);
  for (const f of shown) {
    const where = f.line > 0 ? `${f.path}:${f.line}` : f.path;
    process.stderr.write(`  ${where}\n    ${f.rule}: ${f.excerpt}\n`);
  }
  if (findings.length > shown.length) {
    process.stderr.write(`\n  ... and ${findings.length - shown.length} more\n`);
  }

  process.stderr.write("\nby rule:\n");
  for (const [rule, count] of [...byRule.entries()].sort((a, b) => b[1] - a[1])) {
    process.stderr.write(`  ${count}  ${rule}\n`);
  }

  process.stderr.write(
    "\nFIX IT, DO NOT ARGUE WITH IT.\n" +
      "  - A real address, token or key is removed from the change, not allowlisted.\n" +
      "  - A documentation address is 192.0.2.x, 198.51.100.x or 203.0.113.x.\n" +
      "  - A machine path becomes a placeholder such as <HOME> or <WORKTREE>.\n" +
      "  - If a rule is genuinely wrong, the rule is fixed in scripts/privacy-scan.mjs\n" +
      "    and the fix is justified in the commit message.\n\n",
  );
  return 1;
}

function main(argv) {
  const args = argv.slice(2);

  if (args.includes("--help") || args.includes("-h")) {
    process.stdout.write(
      "usage: privacy-scan.mjs                  scan the staged diff (pre-commit)\n" +
        "       privacy-scan.mjs --path <p>...    scan whole files or tracked directories\n" +
        "       privacy-scan.mjs --staged         explicit form of the default\n",
    );
    return 0;
  }

  try {
    if (args[0] === "--path") {
      const paths = args.slice(1);
      if (paths.length === 0) {
        process.stderr.write("privacy gate: --path needs at least one path\n");
        return 2;
      }
      return report(pathFindings(paths), `files: ${paths.join(" ")}`);
    }
    return report(stagedFindings(), "staged diff");
  } catch (error) {
    process.stderr.write(`privacy gate: could not run: ${error?.message ?? error}\n`);
    return 2;
  }
}

// Only run when executed, not when imported by the tests.
const invokedDirectly =
  process.argv[1] && relative(process.cwd(), process.argv[1]).endsWith("privacy-scan.mjs");
if (invokedDirectly) {
  process.exit(main(process.argv));
}

export {
  DOC_RANGES,
  SAFE_LITERALS,
  forbiddenPathFinding,
  isBinaryPath,
  isDocumentationAddress,
  isPlaceholderMac,
  isStructuralFalsePositive,
  scanText,
};
