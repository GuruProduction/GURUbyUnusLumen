package com.unuslumen.app.preferences.domain.model

/**
 * Model providers a user can connect GURU to.
 *
 * UnusLumen stays in the list for the future day its own inference ships
 * at scale (UI shows it as "Coming soon"); it remains the default id so
 * existing installs keep working untouched.
 */
enum class AiProvider(val id: Int) {
    None(id = 0),
    OpenAI(id = 1),
    Anthropic(id = 2),
    Google(id = 3),
    XAI(id = 4),
    Ollama(id = 5),
    UnusLumen(id = 6),
    OpenAICompat(id = 7);
}

fun Int.toAiProvider() = AiProvider.entries.firstOrNull { entry -> entry.id == this } ?: AiProvider.None

/**
 * The fixed canonical endpoint for cloud brands. null means the provider
 * asks the user for a base URL (Ollama, OpenAI-compat) or has none (None).
 * Shared by the AI repository (executor wiring) and the Integrations UI
 * (static endpoint hint), hence living here with the enum.
 */
fun AiProvider.fixedBaseUrl(): String? = when (this) {
    AiProvider.OpenAI -> "https://api.openai.com/v1"
    AiProvider.Anthropic -> "https://api.anthropic.com"
    AiProvider.Google -> "https://generativelanguage.googleapis.com"
    AiProvider.XAI -> "https://api.x.ai/v1"
    else -> null
}