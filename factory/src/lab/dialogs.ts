import type { Device } from "./device.js";

/**
 * Android system dialogs that appear before the app owns the screen. The most
 * common on modern emulators is the 16 KB page-size compatibility warning,
 * which sits on top of every cold launch until it is dismissed. The lab clears
 * them so a journey sees the app, not the OS.
 */
const DISMISS_LABELS = ["Don't Show Again", "DON'T SHOW AGAIN", "OK"];

export async function dismissSystemDialogs(device: Device, maxRounds = 3): Promise<string[]> {
  const dismissed: string[] = [];

  for (let round = 0; round < maxRounds; round += 1) {
    const nodes = await device.uiHierarchy();
    const dialogText = nodes.find((node) => node.text === "Android App Compatibility");
    if (!dialogText) break;

    let pressed = false;
    for (const label of DISMISS_LABELS) {
      const button = nodes.find((node) => node.text === label && node.clickable);
      if (!button) continue;
      await device.tap(button.center.x, button.center.y);
      dismissed.push(label);
      pressed = true;
      break;
    }

    if (!pressed) break;
    await new Promise((resolve) => setTimeout(resolve, 1_500));
  }

  return dismissed;
}

export async function isPackageForeground(device: Device, packageName: string): Promise<boolean> {
  const output = await device.adb(["shell", "dumpsys", "activity", "activities"]);
  const resumed = /mResumedActivity[^\n]*/.exec(output)?.[0] ?? "";
  return resumed.includes(packageName);
}
