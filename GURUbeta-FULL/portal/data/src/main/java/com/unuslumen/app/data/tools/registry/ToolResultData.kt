package com.unuslumen.app.data.tools.registry

/**
 * ToolResultData — Marker interface for all tool result data classes.
 *
 * Every tool set's @Serializable result classes implement this so the
 * dispatcher can handle them generically without knowing specific types.
 */
interface ToolResultData