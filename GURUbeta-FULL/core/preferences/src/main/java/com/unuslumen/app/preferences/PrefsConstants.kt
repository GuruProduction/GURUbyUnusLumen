package com.unuslumen.app.preferences

object PrefsConstants {

    const val SETTINGS_PREFERENCES = "settings_preferences"
    const val SETTINGS_THEME_KEY = "settings_theme"
    const val SETTINGS_MATERIAL_YOU = "material_you"
    const val DEFAULT_START_UP_SCREEN_KEY = "default_start_up_screen"
    const val SHOW_COMPLETED_TASKS_KEY = "show_completed_tasks"
    const val SHOW_ALL_NOTES_KEY = "show_all_notes"
    const val TASKS_ORDER_KEY = "tasks_order"
    const val NOTE_VIEW_KEY = "note_view"
    const val BOOKMARK_VIEW_KEY = "bookmark_view"
    const val NOTES_ORDER_KEY = "notes_order"
    const val BOOKMARK_ORDER_KEY = "bookmark_order"
    const val JOURNAL_ORDER_KEY = "journal_order"
    const val EXCLUDED_CALENDARS_KEY = "excluded_calendars"
    const val CALENDAR_VIEW_MODE_KEY = "calendar_view_mode"
    const val FIRST_DAY_OF_WEEK_KEY = "first_day_of_week"
    const val APP_FONT_KEY = "app_font"

    // Name-keyed font selection: a FontRegistry bundled id or a guru_fonts
    // file name. APP_FONT_KEY stays as the legacy int key read once on boot
    // so existing installs keep their saved choice.
    const val APP_FONT_NAME_KEY = "app_font_name"

    const val BLOCK_SCREENSHOTS_KEY = "block_screen_shots"
    const val LOCK_APP_KEY = "lock_app"
    const val FONT_SIZE_KEY = "font_size"

    const val USER_NAME_KEY = "user_name"

    const val AI_PROVIDER_KEY = "ai_api"
    const val AI_TOOLS_ENABLED_KEY = "ai_tools_enabled"

    // BYO model connect: the user's own model endpoint. Key and URL stay on
    // device only, never uploaded. Cloud brands ignore BYO_BASE_URL_KEY (they
    // carry fixed endpoints); Ollama and OpenAI-compatible read all three.
    const val BYO_BASE_URL_KEY = "byo_base_url"
    const val BYO_API_KEY_KEY = "byo_api_key"
    const val BYO_MODEL_NAME_KEY = "byo_model_name"

    // BYO wire options: engine defaults applied when no server config exists.
    // All stored as strings, parsed at read time; invalid or blank falls back
    // to the LlmConfig data class defaults.
    const val BYO_TEMPERATURE_KEY = "byo_temperature"
    const val BYO_MAX_TOKENS_KEY = "byo_max_tokens"
    const val BYO_CONTEXT_WINDOW_KEY = "byo_context_window"
    const val BYO_THINKING_LEVEL_KEY = "byo_thinking_level"
    const val BYO_TOP_P_KEY = "byo_top_p"
    const val BYO_TOP_K_KEY = "byo_top_k"
    const val BYO_REPEAT_PENALTY_KEY = "byo_repeat_penalty"
    const val BYO_SEED_KEY = "byo_seed"

    const val EXTERNAL_NOTES_ENABLED = "external_notes_enabled"
    const val EXTERNAL_NOTES_FOLDER_URI = "markdown_note_folder_uri"
    const val EXTERNAL_NOTES_FOLDER_PATH = "markdown_note_folder_path"

    const val AUTO_BACKUP_ENABLED = "auto_backup_enabled"
    const val AUTO_BACKUP_FOLDER_URI = "auto_backup_folder_uri"
    const val AUTO_BACKUP_FREQUENCY = "auto_backup_frequency"
    const val AUTO_BACKUP_FREQUENCY_AMOUNT = "auto_backup_frequency_amount"

    const val GURU_FLOATING_ORB_ENABLED = "guru_floating_orb_enabled"

    // Guru Theme Customization
    const val GURU_CUSTOM_THEME_KEY = "guru_custom_theme"
    const val GURU_LAYOUT_CONFIG_KEY = "guru_layout_config"

    // Thinking level for AI responses
    const val THINKING_LEVEL_KEY = "thinking_level"

    // Vision: Guru sees the user's current screen with every message
    const val VISION_ENABLED_KEY = "vision_enabled"

    // Permission gate first-launch tracking
    const val PERMISSION_GATE_SHOWN_KEY = "permission_gate_shown"
}