export interface AdbConfig {
  serial: string;
  adbPath?: string;
  defaultTimeoutMs?: number;
}

export interface UiNode {
  text: string;
  resourceId: string;
  className: string;
  contentDesc: string;
  bounds: string;
  center: { x: number; y: number };
  clickable: boolean;
  enabled: boolean;
  focused: boolean;
  packageName: string;
}

export interface Screenshot {
  png: Buffer;
  width: number;
  height: number;
  capturedAt: number;
}

export interface DeviceInfo {
  serial: string;
  model: string;
  manufacturer: string;
  sdk: number;
  release: string;
  density: string;
}
