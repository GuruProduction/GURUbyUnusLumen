import { createHash } from "node:crypto";
import { mkdir, writeFile, copyFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join } from "node:path";
import type { LogcatEntry } from "../lab/logcat.js";
import type { Screenshot, UiNode } from "../lab/types.js";
import type { BundleDraft, BundleManifest, BundlePaths } from "./types.js";

export function bundleId(draft: BundleDraft): string {
  const hash = createHash("sha256");
  hash.update(draft.journeyName);
  hash.update(draft.buildSha);
  hash.update(draft.failure.message);
  hash.update(JSON.stringify(draft.steps));
  return hash.digest("hex").slice(0, 16);
}

export function bundlePaths(rootDir: string, id: string): BundlePaths {
  const root = join(rootDir, id);
  return {
    root,
    manifest: join(root, "manifest.json"),
    logcat: join(root, "logcat.txt"),
    steps: join(root, "steps.json"),
    screenshots: join(root, "screenshots"),
    hierarchy: join(root, "hierarchy"),
    recording: join(root, "recording.mp4"),
  };
}

export interface BundleArtifacts {
  logcat: LogcatEntry[];
  screenshots: Array<{ name: string; shot: Screenshot }>;
  hierarchies: Array<{ name: string; nodes: UiNode[] }>;
  recordingPath?: string;
  notes?: string[];
}

export async function writeBundle(
  rootDir: string,
  draft: BundleDraft,
  artifacts: BundleArtifacts,
): Promise<BundleManifest> {
  const id = bundleId(draft);
  const paths = bundlePaths(rootDir, id);

  await mkdir(paths.screenshots, { recursive: true });
  await mkdir(paths.hierarchy, { recursive: true });

  const manifest: BundleManifest = {
    id,
    createdAt: new Date().toISOString(),
    journeyName: draft.journeyName,
    persona: draft.persona,
    buildSha: draft.buildSha,
    versionName: draft.versionName,
    versionCode: draft.versionCode,
    packageName: draft.packageName,
    device: draft.device,
    failure: draft.failure,
    steps: draft.steps,
    logcatLineCount: artifacts.logcat.length,
    screenshotCount: artifacts.screenshots.length,
    replayAttempts: 0,
    replayConfirmed: false,
    notes: artifacts.notes ?? [],
  };

  await writeFile(paths.manifest, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
  await writeFile(paths.steps, `${JSON.stringify(draft.steps, null, 2)}\n`, "utf8");
  await writeFile(
    paths.logcat,
    artifacts.logcat.map((entry) => entry.raw).join("\n") + "\n",
    "utf8",
  );

  for (const { name, shot } of artifacts.screenshots) {
    await writeFile(join(paths.screenshots, name), shot.png);
  }

  for (const { name, nodes } of artifacts.hierarchies) {
    await writeFile(
      join(paths.hierarchy, name),
      `${JSON.stringify(nodes, null, 2)}\n`,
      "utf8",
    );
  }

  if (artifacts.recordingPath && existsSync(artifacts.recordingPath)) {
    await copyFile(artifacts.recordingPath, paths.recording);
  }

  return manifest;
}

export async function readManifest(path: string): Promise<BundleManifest> {
  const { readFile } = await import("node:fs/promises");
  const raw = await readFile(path, "utf8");
  return JSON.parse(raw) as BundleManifest;
}
