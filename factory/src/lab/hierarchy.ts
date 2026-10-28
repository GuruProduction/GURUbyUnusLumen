import type { UiNode } from "./types.js";

const BOUNDS_PATTERN = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/;

export function parseBounds(bounds: string): { x1: number; y1: number; x2: number; y2: number } | null {
  const match = BOUNDS_PATTERN.exec(bounds);
  if (!match) return null;
  return {
    x1: Number(match[1]),
    y1: Number(match[2]),
    x2: Number(match[3]),
    y2: Number(match[4]),
  };
}

export function parseUiHierarchy(xml: string): UiNode[] {
  const nodes: UiNode[] = [];

  // uiautomator emits a single-line XML document. Scan node tags directly so
  // we do not depend on a full XML parser for something this regular.
  const nodePattern = /<node\b([^>]*?)\/?>/g;
  let match: RegExpExecArray | null;

  while ((match = nodePattern.exec(xml)) !== null) {
    const attrText = match[1];
    const attrs = new Map<string, string>();
    const attrPattern = /([A-Za-z0-9_:-]+)="([^"]*)"/g;
    let attrMatch: RegExpExecArray | null;

    while ((attrMatch = attrPattern.exec(attrText)) !== null) {
      attrs.set(attrMatch[1], decodeXmlEntities(attrMatch[2]));
    }

    const bounds = attrs.get("bounds") ?? "";
    const parsed = parseBounds(bounds);
    if (!parsed) continue;

    nodes.push({
      text: attrs.get("text") ?? "",
      resourceId: attrs.get("resource-id") ?? "",
      className: attrs.get("class") ?? "",
      contentDesc: attrs.get("content-desc") ?? "",
      bounds,
      center: {
        x: Math.round((parsed.x1 + parsed.x2) / 2),
        y: Math.round((parsed.y1 + parsed.y2) / 2),
      },
      clickable: attrs.get("clickable") === "true",
      enabled: attrs.get("enabled") === "true",
      focused: attrs.get("focused") === "true",
      packageName: attrs.get("package") ?? "",
    });
  }

  return nodes;
}

function decodeXmlEntities(value: string): string {
  return value
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

export function findByText(nodes: UiNode[], text: string): UiNode | undefined {
  return nodes.find((node) => node.text === text);
}

export function findByTextContaining(nodes: UiNode[], fragment: string): UiNode | undefined {
  const lowered = fragment.toLowerCase();
  return nodes.find((node) => node.text.toLowerCase().includes(lowered));
}

export function findByResourceId(nodes: UiNode[], resourceId: string): UiNode | undefined {
  return nodes.find((node) => node.resourceId === resourceId);
}

export function findClickable(nodes: UiNode[], predicate: (node: UiNode) => boolean): UiNode | undefined {
  return nodes.find((node) => node.clickable && predicate(node));
}

export function describeNode(node: UiNode): string {
  const label = node.text || node.contentDesc || node.resourceId || node.className;
  return `${label} @ ${node.center.x},${node.center.y} (${node.bounds})`;
}
