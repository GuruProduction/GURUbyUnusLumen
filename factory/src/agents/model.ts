import { readFile } from "node:fs/promises";

/**
 * The model client.
 *
 * Everything the factory learns, fixes and builds has to run without a human
 * sitting in the chat window. That means it cannot depend on this session. It
 * talks to the local Ollama endpoint, which is already running and already has
 * capable models pulled.
 *
 * Deliberately no streaming and no tool-calling loop: the fix agent needs a
 * single structured answer, and a one-shot request is the easiest thing in the
 * world to reason about when a patch has to be reproducible.
 */

export interface ModelConfig {
  baseUrl?: string;
  model?: string;
  timeoutMs?: number;
  temperature?: number;
}

export interface ModelMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

export interface ModelReply {
  content: string;
  model: string;
  durationMs: number;
}

const DEFAULT_BASE_URL = "http://127.0.0.1:11434";
// The factory runs on DeepSeek flash. It is fast, cheap enough to run every
// night across many lanes, and strong at code. Override per lane with
// FACTORY_MODEL when a harder job wants a bigger model.
const DEFAULT_MODEL = "deepseek-v4.1-flash:cloud";

/**
 * OLLAMA_HOST is commonly set to a bare host like "127.0.0.1" or
 * "127.0.0.1:11434" with no scheme, which makes fetch() throw
 * ERR_INVALID_URL. Normalise whatever we were given into a real URL rather
 * than trusting the environment to be well-formed.
 */
export function resolveBaseUrl(configured?: string): string {
  const raw = (configured ?? process.env.OLLAMA_HOST ?? DEFAULT_BASE_URL).trim();
  if (raw.length === 0) return DEFAULT_BASE_URL;

  const withScheme = /^https?:\/\//i.test(raw) ? raw : `http://${raw}`;
  // A bare host has no port; Ollama's default port is 11434.
  const withoutTrailingSlash = withScheme.replace(/\/+$/, "");

  try {
    const parsed = new URL(withoutTrailingSlash);
    if (!parsed.port) parsed.port = "11434";
    return parsed.origin;
  } catch {
    return DEFAULT_BASE_URL;
  }
}

export async function askModel(
  messages: ModelMessage[],
  config: ModelConfig = {},
): Promise<ModelReply> {
  const baseUrl = resolveBaseUrl(config.baseUrl);
  const model = config.model ?? process.env.FACTORY_MODEL ?? DEFAULT_MODEL;
  const timeoutMs = config.timeoutMs ?? 300_000;
  const startedAt = Date.now();

  const url = `${baseUrl}/api/chat`;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      signal: controller.signal,
      body: JSON.stringify({
        model,
        messages,
        stream: false,
        options: {
          temperature: config.temperature ?? 0.1,
        },
      }),
    });

    if (!response.ok) {
      const body = await response.text();
      throw new Error(`Model request failed (${response.status}): ${body.slice(0, 400)}`);
    }

    const payload = (await response.json()) as {
      message?: { content?: string };
      error?: string;
    };

    if (payload.error) throw new Error(`Model error: ${payload.error}`);

    const content = payload.message?.content ?? "";
    if (content.trim().length === 0) {
      throw new Error("Model returned an empty reply");
    }

    return { content, model, durationMs: Date.now() - startedAt };
  } finally {
    clearTimeout(timer);
  }
}

/** Reads a file for a model prompt, with a hard size guard. */
export async function readForModel(path: string, maxBytes = 120_000): Promise<string> {
  const content = await readFile(path, "utf8");
  if (Buffer.byteLength(content, "utf8") > maxBytes) {
    return content.slice(0, maxBytes) + "\n\n[truncated: file larger than prompt budget]";
  }
  return content;
}

// bust

// bust
