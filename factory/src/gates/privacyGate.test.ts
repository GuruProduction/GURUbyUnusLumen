// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.

/**
 * THE PRIVACY GATE'S OWN TESTS.
 *
 * A scanner that has only ever returned "clean" has proven nothing. Every rule below is
 * asserted twice: once with a value that must be caught, and once with the nearest thing that
 * must not be. Without the second half the gate is an annoyance, and an annoying gate is one a
 * developer disables, which is exactly the state this project was in.
 *
 * WHY THE FIXTURES ARE BUILT RATHER THAN WRITTEN. This file is committed to a public
 * repository and is scanned by the same gate it tests, so a literal private-range address or
 * a literal token-shaped string here would be refused by the very rule it is proving. Writing
 * them out and allowlisting the file would weaken the gate for every future change to it. So
 * each fixture is assembled at runtime from parts, which produces the exact bytes under test
 * while the source carries nothing that looks like the thing it is testing for.
 */

import { describe, expect, it } from "bun:test";
import {
  forbiddenPathFinding,
  isBinaryPath,
  isDocumentationAddress,
  isPlaceholderMac,
  scanText,
} from "../../../scripts/privacy-scan.mjs";

/** Assemble a dotted quad from its parts, so no quad literal appears in this file. */
const quad = (...octets: number[]): string => octets.join(".");

/** Assemble a hostname-shaped string with a colon separator for the MAC cases. */
const mac = (groups: string[]): string => groups.join(":");

/** A string built by concatenation, so a credential pattern never appears literally. */
const join = (...parts: string[]): string => parts.join("");

const rules = (text: string): string[] => scanText("probe.kt", text).map((f) => f.rule);

const has = (text: string): boolean => scanText("probe.kt", text).length > 0;

describe("an address that is not documentation is refused", () => {
  it("refuses a private-range host", () => {
    expect(has(`A television at ${quad(192, 168, 10, 20)} is not routable`)).toBe(true);
  });

  it("refuses a ten-dot address", () => {
    expect(has(`host = ${quad(10, 20, 30, 40)}`)).toBe(true);
  });

  it("refuses a public address", () => {
    expect(has(`the gateway answered from ${quad(81, 140, 52, 9)}`)).toBe(true);
  });

  it("accepts every documentation range", () => {
    expect(has(`Host address, e.g. ${quad(192, 0, 2, 10)}`)).toBe(false);
    expect(has(`Host address, e.g. ${quad(198, 51, 100, 25)}`)).toBe(false);
    expect(has(`Host address, e.g. ${quad(203, 0, 113, 7)}`)).toBe(false);
  });

  it("accepts loopback and the unspecified address", () => {
    expect(has(`url is http://${quad(127, 0, 0, 1)}:8787/admin`)).toBe(false);
    expect(has(`binds ${quad(0, 0, 0, 0)} on boot`)).toBe(false);
  });

  it("accepts a dotted quad that continues into a longer number", () => {
    expect(has(`"${quad(1, 3, 6, 1)}.2.1.1.1.0",   // sysDescr`)).toBe(false);
  });

  it("accepts an address explicitly marked as a version", () => {
    expect(has(`app version v3.49 / build ${quad(1, 2, 3, 4)}`)).toBe(false);
  });

  it("does not excuse an address merely because it is in a comment", () => {
    // The whole point: a comment is where a real address is most often pasted.
    expect(has(`// my router lives at ${quad(192, 168, 1, 1)}`)).toBe(true);
    expect(has(`* television at ${quad(172, 20, 10, 1)}`)).toBe(true);
  });
});

describe("documentation range classification", () => {
  it("recognises the three RFC 5737 ranges", () => {
    expect(isDocumentationAddress(quad(192, 0, 2, 99))).toBe(true);
    expect(isDocumentationAddress(quad(198, 51, 100, 1))).toBe(true);
    expect(isDocumentationAddress(quad(203, 0, 113, 255))).toBe(true);
  });

  it("does not mistake a real range for one", () => {
    expect(isDocumentationAddress(quad(192, 0, 3, 1))).toBe(false);
    expect(isDocumentationAddress(quad(192, 168, 10, 1))).toBe(false);
    expect(isDocumentationAddress(quad(203, 0, 114, 1))).toBe(false);
  });
});

describe("IPv6 and MAC addresses", () => {
  it("refuses a real-looking IPv6 address", () => {
    const address = ["2001", "0db8", "85a3", "0000", "0000", "8a2e", "0370", "7334"].join(":");
    expect(has(`the tunnel is ${address}`)).toBe(true);
  });

  it("refuses a real-looking MAC address", () => {
    // A tail that spells nothing, so the placeholder rule cannot excuse it.
    expect(has(`${mac(["d8", "31", "34", "5f", "a9", "02"])} was the television`)).toBe(true);
  });

  it("accepts the textbook placeholder MAC", () => {
    expect(has(`${mac(["d8", "31", "34", "aa", "bb", "cc"])} is a placeholder`)).toBe(false);
  });

  it("recognises a placeholder tail", () => {
    expect(isPlaceholderMac(mac(["aa", "bb", "cc", "dd", "ee", "ff"]))).toBe(true);
    expect(isPlaceholderMac(mac(["d8", "31", "34", "aa", "bb", "cc"]))).toBe(true);
    expect(isPlaceholderMac(mac(["d8", "31", "34", "5f", "a9", "02"]))).toBe(false);
  });

  it("does not report a MAC address as IPv6 as well", () => {
    const found = rules(mac(["aa", "bb", "cc", "dd", "ee", "ff"]));
    expect(found).not.toContain("IPv6 address");
  });
});

describe("credentials are refused wherever they appear", () => {
  it("refuses a private key block", () => {
    const header = join("-----BEGIN ", "RSA ", "PRIVATE KEY-----");
    expect(has(`${header}\nAAAA\n-----END RSA PRIVATE KEY-----`)).toBe(true);
  });

  it("refuses an OpenAI-shaped key", () => {
    const key = join("sk-", "proj-", "abcdefghijklmnopqrstuvwxyz0123456789");
    expect(has(`val key = "${key}"`)).toBe(true);
  });

  it("refuses an Anthropic-shaped key", () => {
    const key = join("sk-", "ant-", "api03-abcdefghijklmnopqrstuvwxyz");
    expect(has(`val key = "${key}"`)).toBe(true);
  });

  it("refuses a GitHub token", () => {
    const token = join("gh", "p_", "abcdefghijklmnopqrstuvwxyz0123456789");
    expect(has(`token = ${token}`)).toBe(true);
  });

  it("refuses an AWS access key id", () => {
    const id = join("AKIA", "IOSFODNN7EXAMPLE");
    expect(has(`aws_access_key_id = ${id}`)).toBe(true);
  });

  it("refuses a credential assignment with a real-looking value", () => {
    expect(has(`password = "${join("hunter2", "hunter2", "hunter2")}"`)).toBe(true);
    expect(has(`api_key: "${join("a8f5f167", "f44f4964e6c998dee827110c")}"`)).toBe(true);
  });

  it("accepts a placeholder value", () => {
    expect(has('password = "your-password-here"')).toBe(false);
    expect(has('apiKey = "xxxxxxxxxxxx"')).toBe(false);
    expect(has('token = "${GITHUB_TOKEN}"')).toBe(false);
    expect(has('secret = "<YOUR_SECRET>"')).toBe(false);
  });
});

describe("machine paths are refused", () => {
  it("refuses the owner's home directory", () => {
    // Assembled so this file does not itself name a machine.
    const path = join("/Us", "ers/someone/GURU-dev/lux.md");
    expect(has(`see ${path}`)).toBe(true);
  });

  it("refuses a worktree path", () => {
    const path = join("/Us", "ers/someone/guru", "-worktrees/340-guru-name");
    expect(has(`cd ${path}`)).toBe(true);
  });

  it("accepts a placeholder path", () => {
    expect(has("cd <HOME>/GURU-dev")).toBe(false);
    expect(has("cd ~/GURU-dev")).toBe(false);
  });
});

describe("forbidden files are refused by name", () => {
  it("refuses a keystore", () => {
    expect(forbiddenPathFinding("app/release.jks")).not.toBeNull();
  });

  it("refuses an environment file", () => {
    expect(forbiddenPathFinding("GURUbeta-FULL/.env")).not.toBeNull();
    expect(forbiddenPathFinding(".env.local")).not.toBeNull();
  });

  it("refuses local.properties", () => {
    expect(forbiddenPathFinding("GURUbeta-FULL/local.properties")).not.toBeNull();
  });

  it("refuses a private key file", () => {
    expect(forbiddenPathFinding(join("keys/id_", "ed25519"))).not.toBeNull();
  });

  it("accepts ordinary source files", () => {
    expect(forbiddenPathFinding("portal/data/src/main/LocalRoute.kt")).toBeNull();
    expect(forbiddenPathFinding("README.md")).toBeNull();
  });
});

describe("binaries are not scanned as text", () => {
  it("recognises an apk and a shared object", () => {
    expect(isBinaryPath("app-release.apk")).toBe(true);
    expect(isBinaryPath("libguru_curl.so")).toBe(true);
    expect(isBinaryPath("font.ttf")).toBe(true);
  });

  it("does not treat source as binary", () => {
    expect(isBinaryPath("LocalRoute.kt")).toBe(false);
    expect(isBinaryPath("README.md")).toBe(false);
  });
});

describe("prose is scanned too, because that is where it has shipped", () => {
  it("refuses an address in release-note prose", () => {
    const note = `Fixed discovery. Tested from ${quad(192, 168, 0, 42)} against the television.`;
    expect(has(note)).toBe(true);
  });

  it("passes a release note that uses a placeholder", () => {
    const note = `Fixed discovery. Tested from ${quad(192, 0, 2, 42)} against the television.`;
    expect(has(note)).toBe(false);
  });
});
