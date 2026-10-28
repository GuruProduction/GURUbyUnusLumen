export type StepKind =
  | "launch"
  | "force_stop"
  | "clear_data"
  | "tap"
  | "long_press"
  | "swipe"
  | "type"
  | "key"
  | "wait"
  | "wait_for_text"
  | "screenshot"
  | "grant"
  | "app_op"
  | "shell"
  | "assert_text"
  | "assert_package";

export interface JourneyStep {
  kind: StepKind;
  label?: string;
  x?: number;
  y?: number;
  x2?: number;
  y2?: number;
  durationMs?: number;
  text?: string;
  key?: number | string;
  packageName?: string;
  activity?: string;
  permission?: string;
  operation?: string;
  mode?: "allow" | "deny" | "ignore";
  command?: string;
  timeoutMs?: number;
  waitAfterMs?: number;
}

export interface FailureSignature {
  kind: "fatal_exception" | "anr" | "native_crash" | "assertion" | "manual";
  packageName: string | null;
  message: string;
  matchedLine: string;
  capturedAt: number;
}

export interface BundleManifest {
  id: string;
  createdAt: string;
  journeyName: string;
  persona: string;
  buildSha: string;
  versionName: string;
  versionCode: number;
  packageName: string;
  device: {
    serial: string;
    model: string;
    sdk: number;
    release: string;
  };
  failure: FailureSignature;
  steps: JourneyStep[];
  logcatLineCount: number;
  screenshotCount: number;
  replayAttempts: number;
  replayConfirmed: boolean;
  notes: string[];
}

export interface BundlePaths {
  root: string;
  manifest: string;
  logcat: string;
  steps: string;
  screenshots: string;
  hierarchy: string;
  recording: string;
}

export interface BundleDraft {
  journeyName: string;
  persona: string;
  buildSha: string;
  versionName: string;
  versionCode: number;
  packageName: string;
  device: {
    serial: string;
    model: string;
    sdk: number;
    release: string;
  };
  failure: FailureSignature;
  steps: JourneyStep[];
}
