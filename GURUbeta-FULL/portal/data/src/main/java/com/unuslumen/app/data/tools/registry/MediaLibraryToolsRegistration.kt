package com.unuslumen.app.data.tools.registry

/**
 * MediaLibraryToolsRegistration — the registration documentation shim for
 * the media library tool family. The definitions live in
 * com.unuslumen.app.data.tools.MediaToolDefinitionsPlus; registration
 * happens through the standard GeneratedToolRegistrations list, which
 * portal:data carries one row for (the media library trio registers on the
 * real registry chain exactly like the other tool sets).
 *
 * Nothing needs to exist here at runtime; the KSP-less list is manually
 * maintained by design per the app's own comment in ToolRegistration.kt.
 * This object documents what lives there and holds nothing dangling.
 */
object MediaLibraryToolsRegistration {
    /** Registration names for cross-reference when the tools are dispatched. */
    const val ZOOM = "mediaZoom"
    const val SEARCH = "mediaSearch"
    const val RECALL = "mediaRecall"
}