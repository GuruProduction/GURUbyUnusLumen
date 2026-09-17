package com.unuslumen.app.ui.theme

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.unuslumen.app.ui.R

/**
 * The app's font registry — every typeface the font picker can offer.
 *
 * Two sources, one list:
 *
 * 1. BUNDLED FONTS. Real typefaces committed under res/font/ as single-file
 *    TTFs, all SIL Open Font License 1.1 (see res/FONT-LICENSES.md). These
 *    are the picker's library: curated, name-keyed, previewable. Each entry
 *    names the display label exactly as the picker shows it.
 *
 * 2. USER-DROPPED FONTS. Any .ttf/.otf placed in the app's files/guru_fonts/
 *    directory by the human (or the ThemeTool) loads at runtime through
 *    [loadUserFontFamily] and joins the picker list automatically. These
 *    never enter the APK; they stay personal to the device.
 *
 * Font selection is stored as the label string (the same label shown in the
 * picker), resolved here to a family. Legacy int keys from the old four-way
 * picker (0=Default 1=Rubik 2=Monospace 3=SansSerif) resolve through
 * [legacyIntToLabel] so existing installs keep their choice.
 */
object FontRegistry {

    /** One selectable face: registry id, human label, and its bundled TTF res id. */
    data class BundledFont(
        val id: String,
        val label: String,
        val resId: Int,
    )

    /** The curated OFL library committed in res/font/. 36 faces. */
    val bundled: List<BundledFont> = listOf(
        // ── Default & system classics (kept from the old picker) ──
        BundledFont("rubik", "Rubik (default)", R.font.rubik_regular),
        BundledFont("system", "System default", resId = -1), // FontFamily.Default sentinel
        BundledFont("monospace", "Monospace", resId = -2),   // FontFamily.Monospace sentinel
        BundledFont("sans_serif", "Sans Serif", resId = -3), // FontFamily.SansSerif sentinel

        // ── Clean sans ──
        BundledFont("inter", "Inter", R.font.inter),
        BundledFont("manrope", "Manrope", R.font.manrope),
        BundledFont("outfit", "Outfit", R.font.outfit),
        BundledFont("space_grotesk", "Space Grotesk", R.font.space_grotesk),
        BundledFont("sora", "Sora", R.font.sora),
        BundledFont("urbanist", "Urbanist", R.font.urbanist),
        BundledFont("figtree", "Figtree", R.font.figtree),
        BundledFont("plus_jakarta_sans", "Plus Jakarta Sans", R.font.plus_jakarta_sans),
        BundledFont("dm_sans", "DM Sans", R.font.dm_sans),
        BundledFont("poppins", "Poppins", R.font.poppins),
        BundledFont("nunito", "Nunito", R.font.nunito),
        BundledFont("montserrat", "Montserrat", R.font.montserrat),

        // ── Serifs, Portal-warm ──
        BundledFont("fraunces", "Fraunces", R.font.fraunces),
        BundledFont("lora", "Lora", R.font.lora),
        BundledFont("playfair_display", "Playfair Display", R.font.playfair_display),
        BundledFont("libre_caslon_text", "Libre Caslon", R.font.libre_caslon_text),
        BundledFont("crimson_pro", "Crimson Pro", R.font.crimson_pro),
        BundledFont("merriweather", "Merriweather", R.font.merriweather),
        BundledFont("bitter", "Bitter", R.font.bitter),
        BundledFont("cormorant_garamond", "Cormorant Garamond", R.font.cormorant_garamond),
        BundledFont("newsreader", "Newsreader", R.font.newsreader),

        // ── Slab ──
        BundledFont("zilla_slab", "Zilla Slab", R.font.zilla_slab),

        // ── Display & condensed ──
        BundledFont("bebas_neue", "Bebas Neue", R.font.bebas_neue),
        BundledFont("anton", "Anton", R.font.anton),
        BundledFont("archivo_black", "Archivo Black", R.font.archivo_black),
        BundledFont("oswald", "Oswald", R.font.oswald),
        BundledFont("righteous", "Righteous", R.font.righteous),
        BundledFont("unbounded", "Unbounded", R.font.unbounded),
        BundledFont("syne", "Syne", R.font.syne),

        // ── Monospace ──
        BundledFont("jetbrains_mono", "JetBrains Mono", R.font.jetbrains_mono),
        BundledFont("ibm_plex_mono", "IBM Plex Mono", R.font.ibm_plex_mono),
        BundledFont("fira_code", "Fira Code", R.font.fira_code),
        BundledFont("space_mono", "Space Mono", R.font.space_mono),

        // ── Script & handwriting ──
        BundledFont("caveat", "Caveat", R.font.caveat),
        BundledFont("dancing_script", "Dancing Script", R.font.dancing_script),
        BundledFont("pacifico", "Pacifico", R.font.pacifico),
    )

    private val byId = bundled.associateBy { it.id }

    /** All picker options: bundled faces first, then any user-dropped guru_fonts. */
    fun labels(userFonts: List<String>): List<String> =
        bundled.map { it.label } + userFonts

    /** The legacy int key from the old four-way picker, translated to its label. */
    fun legacyIntToLabel(intKey: Int): String = when (intKey) {
        0 -> "System default"
        1 -> "Rubik (default)"
        2 -> "Monospace"
        3 -> "Sans Serif"
        else -> "Rubik (default)"
    }

    /**
     * Resolve a stored font selection (label or guru_fonts file name) to a family.
     *
     * Order: exact guru_fonts file match (user-dropped face wins over a same-named
     * bundled face), then bundled library by id/label, then fallback to Rubik.
     *
     * @param selection stored string from prefs — a bundled id/label or a
     *   guru_fonts file name (without extension)
     * @param userFontNames fonts available in files/guru_fonts/, no extension
     */
    fun resolve(
        selection: String?,
        userFontNames: List<String> = emptyList(),
    ): androidx.compose.ui.text.font.FontFamily {
        if (selection.isNullOrBlank()) return Rubik
        // User-dropped font takes priority when the name matches a file.
        if (userFontNames.contains(selection)) {
            val context = userFontContext ?: return Rubik
            return loadUserFontFamily(context, selection) ?: Rubik
        }
        val bundledFont = byId[selection]
            ?: bundled.find { it.label == selection }
        return bundledFont?.let { familyFor(it) } ?: Rubik
    }

    /** Old int-keyed selections keep resolving so no saved preference breaks. */
    fun resolveLegacy(intKey: Int): androidx.compose.ui.text.font.FontFamily = when (intKey) {
        0 -> androidx.compose.ui.text.font.FontFamily.Default
        2 -> androidx.compose.ui.text.font.FontFamily.Monospace
        3 -> androidx.compose.ui.text.font.FontFamily.SansSerif
        else -> Rubik
    }

    private fun familyFor(font: BundledFont): androidx.compose.ui.text.font.FontFamily = when (font.resId) {
        -1 -> androidx.compose.ui.text.font.FontFamily.Default
        -2 -> androidx.compose.ui.text.font.FontFamily.Monospace
        -3 -> androidx.compose.ui.text.font.FontFamily.SansSerif
        else -> androidx.compose.ui.text.font.FontFamily(Font(font.resId))
    }

    /** Cache of user-font families per (fileName) for the current process. */
    private val userFontCache = mutableMapOf<String, androidx.compose.ui.text.font.FontFamily>()

    /** Load a user-dropped .ttf/.otf from files/guru_fonts/ by file name. */
    fun loadUserFontFamily(context: Context, name: String): androidx.compose.ui.text.font.FontFamily? {
        synchronized(userFontCache) {
            userFontCache[name]?.let { return it }
        }
        return try {
            val dir = java.io.File(context.filesDir, "guru_fonts")
            val file = java.io.File(dir, "$name.ttf").takeIf { it.exists() }
                ?: java.io.File(dir, "$name.otf").takeIf { it.exists() }
                ?: return null
            val family = androidx.compose.ui.text.font.FontFamily(
                androidx.compose.ui.text.font.Font(file)
            )
            synchronized(userFontCache) { userFontCache[name] = family }
            family
        } catch (e: Exception) {
            null
        }
    }

    private var userFontContext: Context? = null

    /** Called once from the app layer so user-font loading has a Context. */
    fun init(context: Context) {
        userFontContext = context.applicationContext
    }

    /** The single source of truth for what font the app currently renders in. */
    fun currentFamily(selection: String): androidx.compose.ui.text.font.FontFamily {
        val context = userFontContext
        val userFonts = if (context != null) listUserFonts(context) else emptyList()
        return resolve(selection, userFonts)
    }

    /** Names (no extension) of user-dropped fonts in files/guru_fonts/. */
    fun listUserFonts(context: Context): List<String> = try {
        val dir = java.io.File(context.filesDir, "guru_fonts")
        dir.listFiles()
            ?.filter { it.extension in listOf("ttf", "otf") }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}