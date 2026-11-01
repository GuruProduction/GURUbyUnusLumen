// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

/**
 * The IR code library: what a named button on a named device means. Issue #187.
 *
 * WHY A LIBRARY AND NOT A TABLE IN THE CODE. The issue's whole point is that GURU
 * controls a television whatever make it is. A telly's identity is a (protocol,
 * address, command) triple per button, the public databases hold millions of
 * those triples, and none of that belongs compiled into a binary that ships to a
 * phone. So the library is data, it is parsed here, and the parsers are pure so a
 * JVM test pins them against real files from the real databases.
 *
 * THREE FORMATS, because those are the three the issue names as freely usable:
 *
 *  - Flipper's `.ir` files (key=value blocks, `type: parsed` or `type: raw`).
 *  - LIRC's `lircd.conf` (a remote header plus `begin codes` or `begin raw_codes`).
 *  - Pronto hex (the four-word-header hex string a Pronto remote records).
 *
 * WHAT THE PARSERS REFUSE TO DO. They do not guess. A block with no protocol, an
 * unparsable address, a malformed Pronto string: each is reported as a problem
 * with a line number rather than dropped in silence, because a library that
 * quietly loses half its entries is a library whose failures look like a device
 * that will not respond.
 */

/** One named code: the button, and what has to be transmitted to press it. */
data class IrCode(
    /** The button name as the database spells it, normalised to a token. */
    val button: String,
    /** The protocol, when the source named one. Null for a raw capture. */
    val protocol: IrProtocolName?,
    /** The address field, or 0 when the source has no such notion. */
    val address: Int = 0,
    /** The command field, or 0 for a raw capture. */
    val command: Int = 0,
    /** For a raw capture: the timings themselves. Null for a synthesised code. */
    val rawPulses: IntArray? = null,
    /** Carrier for a raw capture, or the protocol's own default. */
    val carrierHz: Int = 38000,
    /** How many times to repeat the frame, which some devices need at least twice. */
    val repeats: Int = 1,
    /** Where this code came from, for a result that has to explain itself. */
    val source: String,
    /** How many times a held button repeats, when the file states it. */
    val holdRepeats: Int = 0,
    /**
     * The timing specification, when the source was an LIRC file.
     *
     * A LIRC remote declares its own header, one and zero timings, so the file has
     * already said what the pulses are and no protocol needs naming. Dropping this
     * would throw away decades of coverage in the LIRC database, which is the largest
     * single source of real remote codes there is.
     */
    val lircTiming: LircTiming? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IrCode) return false
        return button == other.button &&
            protocol == other.protocol &&
            address == other.address &&
            command == other.command &&
            carrierHz == other.carrierHz &&
            repeats == other.repeats &&
            source == other.source &&
            (rawPulses?.contentEquals(other.rawPulses) ?: (other.rawPulses == null))
    }

    override fun hashCode(): Int {
        var result = button.hashCode()
        result = 31 * result + (protocol?.hashCode() ?: 0)
        result = 31 * result + address
        result = 31 * result + command
        result = 31 * result + carrierHz
        result = 31 * result + (rawPulses?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * A LIRC remote's own pulse timings.
 *
 * [header], [one] and [zero] are each a mark and a space in microseconds, exactly as
 * the lircd.conf file writes them. [bits] is the code width, and [gap] is the space
 * after a frame.
 *
 * WHY THIS IS ENOUGH TO TRANSMIT WITHOUT A PROTOCOL NAME. An IR code is only ever a
 * pulse train, and these fields ARE that train's specification: a header pair, a bit
 * width, a mark/space for a one, a mark/space for a zero, and a gap. A file that
 * supplies all five has said everything the emitter needs.
 */
data class LircTiming(
    val headerMark: Int,
    val headerSpace: Int,
    val oneMark: Int,
    val oneSpace: Int,
    val zeroMark: Int,
    val zeroSpace: Int,
    val bits: Int,
    val gap: Int,
    /** `REVERSE` in the file's flags: bits are sent most significant first. */
    val msbFirst: Boolean = false,
)

/** A device family the library knows, with every button it recorded. */
data class IrDeviceFamily(
    /** What the file calls this device, e.g. a model number or "Samsung". */
    val name: String,
    /** The file it came from, so a result can say where the code lived. */
    val source: String,
    val codes: List<IrCode>,
) {
    /** Case-insensitive lookup by button name, with common spellings folded. */
    fun code(button: String): IrCode? {
        val wanted = IrButton.normalise(button)
        return codes.firstOrNull { IrButton.normalise(it.button) == wanted }
    }

    val buttons: List<String> get() = codes.map { IrButton.normalise(it.button) }.distinct()
}

/**
 * What a parse produced, including everything it could not read.
 *
 * A caller that logs only [families] and hides [problems] is telling half a
 * story, which is the defect class this project keeps removing.
 */
data class IrParseResult(
    val families: List<IrDeviceFamily>,
    val problems: List<String>,
) {
    val codeCount: Int get() = families.sumOf { it.codes.size }
}

/**
 * Button-name folding.
 *
 * Every database spells the same button differently ("Vol_up", "VOL+", "Volume
 * Up", "Ch_next", "CH+"), and a person says a fourth thing ("turn it up"). Folding
 * them onto one token is what lets a spoken request find a recorded code.
 */
object IrButton {

    /** The canonical token for a spoken or written button name. */
    fun normalise(raw: String): String {
        val key = raw.trim().lowercase()
            .replace("_", " ")
            .replace("-", " ")
            .replace(".", " ")
            .replace(Regex("\\s+"), " ")
        return when (key) {
            "power", "pwr", "on off", "toggle power", "standby" -> "power"
            "vol up", "vol+", "volume up", "volumeup", "louder", "turn it up", "turn up" -> "vol_up"
            "vol dn", "vol down", "vol-", "volume down", "volumedown", "quieter", "turn it down", "turn down" -> "vol_dn"
            "mute", "muting", "silence" -> "mute"
            "ch next", "ch+", "channel up", "channelup", "next channel" -> "ch_next"
            "ch prev", "ch previous", "ch-", "channel down", "channeldown", "previous channel" -> "ch_prev"
            "up", "cursor up" -> "up"
            "down", "cursor down" -> "down"
            "left", "cursor left" -> "left"
            "right", "cursor right" -> "right"
            "ok", "enter", "select", "confirm" -> "ok"
            "back", "return", "exit" -> "back"
            "home", "smart hub" -> "home"
            "menu", "settings" -> "menu"
            "input", "source", "av" -> "input"
            "play" -> "play"
            "pause" -> "pause"
            "stop" -> "stop"
            "rewind" -> "rewind"
            "fast forward", "ff", "forward" -> "fast_forward"
            "0", "zero" -> "digit_0"
            "1", "one" -> "digit_1"
            "2", "two" -> "digit_2"
            "3", "three" -> "digit_3"
            "4", "four" -> "digit_4"
            "5", "five" -> "digit_5"
            "6", "six" -> "digit_6"
            "7", "seven" -> "digit_7"
            "8", "eight" -> "digit_8"
            "9", "nine" -> "digit_9"
            else -> key.replace(" ", "_")
        }
    }

    /** Whether a spoken phrase reads as a button name at all, for honest refusals. */
    fun looksLikeButton(raw: String): Boolean {
        val token = normalise(raw)
        return token.isNotBlank() && token.any { it.isLetterOrDigit() }
    }
}

/**
 * The Flipper `.ir` parser.
 *
 * FORMAT, as the real files ship it:
 *
 *     Filetype: IR library file
 *     Version: 1
 *     #
 *     name: Power
 *     type: parsed
 *     protocol: NEC
 *     address: 08 00 00 00
 *     command: 05 00 00 00
 *
 * and for a capture:
 *
 *     name: Power
 *     type: raw
 *     frequency: 38000
 *     duty_cycle: 0.330000
 *     data: 634 2571 505 519 ...
 *
 * Addresses and commands are written as four hex bytes most significant first,
 * so `08 00 00 00` is 0x08 and `EA C7 00 00` is 0xEAC7. Reading them
 * little-endian is the mistake that makes every code wrong but plausible, so the
 * order is stated once here and tested.
 *
 * `#` opens a comment line, and the Flipper convention is that a `# Model: X`
 * line names the device the following blocks belong to. Those names are carried
 * onto the family so a person can pick their telly.
 */
object IrFlipperParser {

    fun parse(text: String, source: String): IrParseResult {
        val lines = text.lines()
        val families = mutableListOf<IrDeviceFamily>()
        val problems = mutableListOf<String>()

        // The device a block belongs to. Files that record one device per file use
        // the file's own header; files with several use "# Model: X" markers.
        var currentModel: String? = null
        val byModel = LinkedHashMap<String, MutableList<IrCode>>()

        var name: String? = null
        var type: String? = null
        var protocolRaw: String? = null
        var addressRaw: String? = null
        var commandRaw: String? = null
        var frequencyRaw: String? = null
        var dataRaw: String? = null
        var blockStartLine = 0

        // Declared before the block functions that call it, because Kotlin resolves a
        // local function by declaration order inside a function body.
        fun resetBlock() {
            name = null
            type = null
            protocolRaw = null
            addressRaw = null
            commandRaw = null
            frequencyRaw = null
            dataRaw = null
        }

        fun flush(lineNumber: Int) {
            if (name == null && type == null) return
            val button = name?.trim()
            if (button.isNullOrBlank()) {
                problems.add("$source line $blockStartLine: a block with no name was skipped.")
                return
            }
            val code = when (type?.trim()?.lowercase()) {
                "parsed" -> {
                    val protocol = IrProtocolName.fromRaw(protocolRaw)
                    if (protocol == null) {
                        problems.add(
                            "$source line $blockStartLine: '$button' names protocol " +
                                "'${protocolRaw ?: "none"}', which this build cannot synthesise.",
                        )
                        return
                    }
                    val address = parseFlipperNumber(addressRaw)
                    val command = parseFlipperNumber(commandRaw)
                    if (address == null || command == null) {
                        problems.add(
                            "$source line $blockStartLine: '$button' has an unreadable " +
                                "address ('${addressRaw ?: ""}') or command ('${commandRaw ?: ""}').",
                        )
                        return
                    }
                    IrCode(
                        button = button,
                        protocol = protocol,
                        address = address,
                        command = command,
                        carrierHz = frequencyRaw?.trim()?.toIntOrNull() ?: defaultCarrier(protocol),
                        source = source,
                    )
                }
                "raw" -> {
                    val pulses = parseIntList(dataRaw)
                    if (pulses.isEmpty()) {
                        problems.add(
                            "$source line $blockStartLine: '$button' is a raw capture with no " +
                                "readable data.",
                        )
                        return
                    }
                    IrCode(
                        button = button,
                        protocol = IrProtocolName.RAW,
                        rawPulses = pulses.toIntArray(),
                        carrierHz = frequencyRaw?.trim()?.toIntOrNull() ?: 38000,
                        source = source,
                    )
                }
                else -> {
                    problems.add(
                        "$source line $blockStartLine: '$button' declares type " +
                            "'${type ?: "none"}', which is neither parsed nor raw.",
                    )
                    return
                }
            }
            val model = currentModel ?: source.substringAfterLast('/')
            byModel.getOrPut(model) { mutableListOf() }.add(code)
        }

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            val lineNumber = index + 1
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("#")) {
                // "# Model: X" starts a new device; other comments are prose.
                val model = line.removePrefix("#").trim().removePrefix("Model:").trim()
                if (line.contains("Model:", ignoreCase = true) && model.isNotEmpty()) {
                    flush(lineNumber)
                    resetBlock()
                    currentModel = model
                }
                return@forEachIndexed
            }

            val colon = line.indexOf(':')
            if (colon <= 0) {
                problems.add("$source line $lineNumber: '$line' is not a key: value line.")
                return@forEachIndexed
            }
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()

            when (key) {
                "name" -> {
                    // A new name starts a new block, so the previous one is closed
                    // first. This is what makes the format self-delimiting.
                    flush(lineNumber)
                    resetBlock()
                    blockStartLine = lineNumber
                    name = value
                }
                "type" -> type = value
                "protocol" -> protocolRaw = value
                "address" -> addressRaw = value
                "command" -> commandRaw = value
                "frequency" -> frequencyRaw = value
                "data" -> dataRaw = value
                "filetype", "version", "duty_cycle" -> Unit
                else -> problems.add("$source line $lineNumber: unknown key '$key' was ignored.")
            }
        }
        flush(lines.size)

        byModel.forEach { (model, codes) ->
            if (codes.isNotEmpty()) {
                families.add(IrDeviceFamily(name = model, source = source, codes = codes))
            }
        }
        return IrParseResult(families, problems)
    }

    /**
     * Read a Flipper four-byte number.
     *
     * THE BYTES ARE LEAST SIGNIFICANT FIRST, and that is not a guess: it is the order
     * the bytes are transmitted in, which is why the file writes them that way. So
     * `04 00 00 00` is 0x04 and `EA C7 00 00` is 0xC7EA.
     *
     * Getting this backwards is the subtlest bug in the whole library, because it
     * produces codes that parse, encode and transmit without complaint and simply do
     * nothing. Reading `04 00 00 00` as 0x04000000 would then take the low byte for a
     * NEC address and send 0x00 instead of 0x04, so every VIZIO power press would be
     * the wrong address. The test that pins this is the one that caught it.
     *
     * A value wider than four bytes is refused rather than truncated.
     */
    fun parseFlipperNumber(raw: String?): Int? {
        val parts = raw?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
        if (parts.isEmpty() || parts.size > 4) return null
        var value = 0
        parts.forEachIndexed { index, part ->
            val byte = part.toIntOrNull(16) ?: return null
            if (byte !in 0..255) return null
            value = value or (byte shl (8 * index))
        }
        return value
    }

    /** A space-separated list of timings, ignoring anything that is not a number. */
    private fun parseIntList(raw: String?): List<Int> =
        raw?.trim()?.split(Regex("\\s+"))
            ?.mapNotNull { it.toIntOrNull() }
            ?.filter { it > 0 }
            ?: emptyList()

    /** Each protocol's own carrier, for files that omit the frequency on a parsed code. */
    fun defaultCarrier(protocol: IrProtocolName): Int = when (protocol) {
        IrProtocolName.SIRC, IrProtocolName.SIRC15, IrProtocolName.SIRC20, IrProtocolName.PIONEER -> 40000
        IrProtocolName.RC5, IrProtocolName.RC5X, IrProtocolName.RC6 -> 36000
        IrProtocolName.KASEIKYO -> 37000
        IrProtocolName.RCA -> 58000
        else -> 38000
    }
}

/**
 * The LIRC `lircd.conf` parser.
 *
 * FORMAT, from the LIRC manual page:
 *
 *     begin remote
 *       name  SAMSUNG
 *       bits           16
 *       flags SPACE_ENC
 *       eps            30
 *       aeps          100
 *       header       4500 4500
 *       one           560 1690
 *       zero          560  560
 *       pre_data_bits   16
 *       pre_data       0xE0E0
 *       gap          5000
 *       min_repeat      2
 *       begin codes
 *           KEY_POWER   0x40BF
 *       end codes
 *     end remote
 *
 * The header/one/zero/gap values ARE the timing specification, so an LIRC entry
 * that carries them can be transmitted without knowing which protocol it is: the
 * file has already said what the pulses are. That is the honest way to support a
 * device no protocol name covers.
 *
 * `pre_data` is sent before the code bits and `post_data` after, which is where
 * many Samsung and LG remotes carry their address.
 */
object IrLircParser {

    fun parse(text: String, source: String): IrParseResult {
        val families = mutableListOf<IrDeviceFamily>()
        val problems = mutableListOf<String>()

        var remoteName: String? = null
        var bits = 0
        var header: List<Int> = emptyList()
        var one: List<Int> = emptyList()
        var zero: List<Int> = emptyList()
        var preData = 0L
        var preDataBits = 0
        var postData = 0L
        var postDataBits = 0
        var gap = 0
        var frequency = 38000
        var minRepeat = 0
        var inCodes = false
        var inRawCodes = false
        var rawName: String? = null
        var rawAccum = mutableListOf<Int>()
        var remoteLine = 0
        // LIRC's REVERSE flag decides bit order, and it belongs to the remote block.
        var flagsMsbFirst = false

        // ONE ACCUMULATOR FOR THE REMOTE BEING READ. Codes are added to this and
        // appended to [families] when the block closes, so a code can never be
        // written into the wrong family or lost between blocks.
        val codes = mutableListOf<IrCode>()

        fun flushRaw() {
            val button = rawName ?: return
            if (rawAccum.isNotEmpty()) {
                codes.add(
                    IrCode(
                        button = button,
                        protocol = IrProtocolName.RAW,
                        rawPulses = rawAccum.toIntArray(),
                        carrierHz = frequency,
                        source = source,
                    ),
                )
            }
            rawName = null
            rawAccum = mutableListOf()
        }

        fun flushRemote(closeLine: Int) {
            flushRaw()
            val name = remoteName
            if (name == null) {
                if (codes.isNotEmpty() || bits > 0) {
                    problems.add("$source line $remoteLine: a remote block with no name was skipped.")
                }
                codes.clear()
                return
            }

            // A timing-carrying remote has said everything a transmitter needs, so its
            // timing specification is attached to every code rather than thrown away.
            // This is what makes the LIRC database usable at all: hundreds of remotes
            // there name no protocol because they do not need to.
            val timing = if (header.size >= 2 && one.size >= 2 && zero.size >= 2 && gap > 0 && bits > 0) {
                LircTiming(
                    headerMark = header[0],
                    headerSpace = header[1],
                    oneMark = one[0],
                    oneSpace = one.getOrElse(1) { 0 },
                    zeroMark = zero[0],
                    zeroSpace = zero.getOrElse(1) { 0 },
                    bits = bits,
                    gap = gap,
                    msbFirst = flagsMsbFirst,
                )
            } else {
                null
            }

            // A raw_codes remote needs neither bits nor timings: its numbers ARE the
            // capture, so it is transmitted exactly as recorded. Only a codes-section
            // remote needs a specification, because its entries are protocol values
            // rather than pulses.
            val needsTiming = codes.any { it.rawPulses == null }
            if (codes.isNotEmpty() && timing == null && needsTiming) {
                problems.add(
                    "$source line $remoteLine: remote '$name' declares " +
                        if (bits > 0) {
                            "$bits bits but no complete header/one/zero timings"
                        } else {
                            "codes but neither a bit width nor timings"
                        } +
                        ", so its ${codes.size} code(s) cannot be transmitted from this file alone.",
                )
                codes.clear()
            }

            if (codes.isNotEmpty()) {
                // A repeat of 2 or more is the file saying the device needs more than
                // one frame, which some Samsung and LG sets genuinely do.
                val adjusted = codes.map { code ->
                    code.copy(
                        lircTiming = timing,
                        repeats = if (minRepeat > 1) minRepeat else code.repeats,
                        holdRepeats = minRepeat,
                    )
                }
                families.add(IrDeviceFamily(name = name, source = source, codes = adjusted))
            }
            codes.clear()
            flagsMsbFirst = false

            inCodes = false
            inRawCodes = false
            remoteName = null
            bits = 0
            header = emptyList()
            one = emptyList()
            zero = emptyList()
            preData = 0
            preDataBits = 0
            postData = 0
            postDataBits = 0
            gap = 0
            minRepeat = 0
        }

        text.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            val lineNumber = index + 1
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return@forEachIndexed
            val head = tokens[0].lowercase()

            when {
                head == "begin" && tokens.getOrNull(1)?.lowercase() == "remote" -> {
                    remoteName = null
                    remoteLine = lineNumber
                }
                head == "end" && tokens.getOrNull(1)?.lowercase() == "remote" -> flushRemote(lineNumber)
                head == "begin" && tokens.getOrNull(1)?.lowercase() == "codes" -> inCodes = true
                head == "end" && tokens.getOrNull(1)?.lowercase() == "codes" -> inCodes = false
                head == "begin" && tokens.getOrNull(1)?.lowercase() == "raw_codes" -> inRawCodes = true
                head == "end" && tokens.getOrNull(1)?.lowercase() == "raw_codes" -> {
                    flushRaw()
                    inRawCodes = false
                }
                head == "name" && !inCodes && !inRawCodes -> remoteName = tokens.drop(1).joinToString(" ")
                head == "bits" -> bits = tokens.getOrNull(1)?.toIntOrNull() ?: 0
                head == "header" -> header = tokens.drop(1).mapNotNull { it.toIntOrNull() }
                head == "one" -> one = tokens.drop(1).mapNotNull { it.toIntOrNull() }
                head == "zero" -> zero = tokens.drop(1).mapNotNull { it.toIntOrNull() }
                head == "gap" || head == "repeat" ->
                    gap = tokens.getOrNull(1)?.toIntOrNull() ?: gap
                head == "frequency" ->
                    frequency = tokens.getOrNull(1)?.toIntOrNull() ?: frequency
                head == "min_repeat" -> minRepeat = tokens.getOrNull(1)?.toIntOrNull() ?: 0
                head == "pre_data" ->
                    preData = tokens.getOrNull(1)?.removePrefix("0x")?.toLongOrNull(16) ?: 0
                head == "pre_data_bits" -> preDataBits = tokens.getOrNull(1)?.toIntOrNull() ?: 0
                head == "post_data" ->
                    postData = tokens.getOrNull(1)?.removePrefix("0x")?.toLongOrNull(16) ?: 0
                head == "post_data_bits" -> postDataBits = tokens.getOrNull(1)?.toIntOrNull() ?: 0
                head == "flags" -> {
                    // Only one flag changes what is transmitted: REVERSE sends the bits
                    // most significant first. Everything else in the flag list is about
                    // tolerance and repeat suppression, which the emitter does not act on.
                    flagsMsbFirst = tokens.drop(1).any { it.equals("REVERSE", ignoreCase = true) }
                }
                head == "eps" || head == "aeps" || head == "duty_cycle" ||
                    head == "toggle_bit_mask" || head == "toggle_bit" || head == "manual_sort" ||
                    head == "suppress_repeat" || head == "rc_code_1" || head == "rc_code_2" -> Unit

                inRawCodes -> {
                    // Two shapes: a name on its own then numbers, or numbers only
                    // under the previous name.
                    // A line that is all numeric continues the current raw code's
                    // timings. Anything else names a new button, so the previous
                    // capture is stored first.
                    val numeric = tokens.all { it.toIntOrNull() != null }
                    if (!numeric) {
                        flushRaw()
                        rawName = tokens.joinToString(" ")
                    } else if (rawName != null) {
                        rawAccum.addAll(tokens.mapNotNull { it.toIntOrNull() }.filter { it > 0 })
                    } else {
                        // Timings with no button name above them cannot be attributed,
                        // and guessing a name would file a code nobody asked for.
                        problems.add(
                            "$source line $lineNumber: raw timings appear before any button name, " +
                                "so they were skipped.",
                        )
                    }
                }

                inCodes -> {
                    if (tokens.size < 2) {
                        problems.add("$source line $lineNumber: code line '$line' has no value.")
                        return@forEachIndexed
                    }
                    val button = tokens[0]
                    val value = tokens[1].removePrefix("0x").toLongOrNull(16)
                    if (value == null) {
                        problems.add(
                            "$source line $lineNumber: '$button' has value '${tokens[1]}' which is " +
                                "not hex.",
                        )
                        return@forEachIndexed
                    }
                    // pre_data is part of the transmitted code, so it is folded into
                    // the value. Dropping it is how an LIRC import produces codes that
                    // are plausible and wrong.
                    //
                    // Arithmetic in Long throughout, then masked to [bits], because a
                    // 16-bit pre_data above a 16-bit code is a 32-bit value and a wider
                    // shift would overflow an Int into a negative number that looks
                    // like a working code.
                    val valueBits = if (bits > 0) (value and ((1L shl bits) - 1)) else value
                    val combined = if (preDataBits > 0 && bits > 0) (preData shl bits) or valueBits else value
                    val full = if (postDataBits > 0) (combined shl postDataBits) or postData else combined
                    // The frame is pre_data bits, then the code bits, then post_data
                    // bits, so the width the timings apply to is their sum. Masking to
                    // the code width alone would truncate the very pre_data that makes
                    // the code specific to the remote.
                    val width = (preDataBits + bits + postDataBits).takeIf { it > 0 }
                        ?: bits.takeIf { it > 0 }
                        ?: 16
                    codes.add(
                        IrCode(
                            button = button.removePrefix("KEY_"),
                            // The protocol is not stated by the file, so it is left
                            // null rather than guessed at.
                            protocol = null,
                            command = (full and ((1L shl width.coerceAtMost(32)) - 1)).toInt(),
                            carrierHz = frequency,
                            repeats = minRepeat.coerceAtLeast(1),
                            source = source,
                        ),
                    )
                }

                else -> problems.add("$source line $lineNumber: unrecognised line '$line' was ignored.")
            }
        }
        flushRemote(text.lines().size)
        return IrParseResult(families, problems)
    }
}

/**
 * The Pronto hex parser.
 *
 * A learned Pronto code is a string of four-hex-digit words:
 *
 *     word 0     0000 for a learned (modulated) code
 *     word 1     the frequency word; carrier Hz = 1000000 / (word1 * 0.241246)
 *     word 2     the number of burst pairs in the once-only sequence
 *     word 3     the number of burst pairs in the repeat sequence
 *     then       the pairs, each a mark then a space, in carrier cycles
 *
 * THE ARITHMETIC THAT MATTERS. Each burst-pair count is a number of CARRIER
 * CYCLES, not microseconds, so the duration is cycles times the carrier period.
 * At the documented 0x006D frequency word the carrier is 38.4 kHz and one cycle
 * is 26.04 us, so 0x0157 (343 cycles) is 8931 us. Reading the counts as
 * microseconds produces a code that is a thousandth of the right length and does
 * nothing at all, which is why this conversion is stated and tested rather than
 * assumed.
 */
object IrProntoParser {

    /** The published conversion constant, in microseconds per cycle divisor. */
    private const val PRONTO_CONSTANT = 0.241246

    /** Why the 0100 form cannot be transmitted, stated once rather than guessed. */
    private const val RAW_0100_PROBLEM =
        "a Pronto 0100 raw code has no carrier modulation; this build transmits modulated " +
            "codes only, so it was skipped"

    fun parse(text: String, source: String, familyName: String = "Pronto"): IrParseResult {
        val problems = mutableListOf<String>()
        val codes = mutableListOf<IrCode>()

        val words = text.trim().split(Regex("\\s+"))
            .mapNotNull { it.trim().toIntOrNull(16) }
            .filter { it in 0..0xFFFF }

        if (words.size < 4) {
            return IrParseResult(
                emptyList(),
                listOf("$source: not a Pronto code, fewer than four header words."),
            )
        }

        if (words[0] == 0x0100) {
            return IrParseResult(emptyList(), listOf("$source: $RAW_0100_PROBLEM."))
        }
        if (words[0] != 0x0000) {
            return IrParseResult(
                emptyList(),
                listOf(
                    "$source: Pronto header word 0 is 0x%04X, which is neither a learned code " +
                        "(0x0000) nor a raw code (0x0100).".format(words[0]),
                ),
            )
        }

        val frequencyWord = words[1]
        if (frequencyWord <= 0) {
            return IrParseResult(emptyList(), listOf("$source: Pronto frequency word is zero."))
        }
        val carrierHz = (1_000_000.0 / (frequencyWord * PRONTO_CONSTANT)).toInt()
        val cycleMicros = 1_000_000.0 / carrierHz

        val onceCount = words[2]
        val repeatCount = words[3]
        val pairs = words.drop(4)
        val expected = (onceCount + repeatCount) * 2
        if (pairs.size < expected) {
            return IrParseResult(
                emptyList(),
                listOf(
                    "$source: Pronto code declares ${onceCount + repeatCount} burst pairs " +
                        "($expected words) but carries ${pairs.size}.",
                ),
            )
        }

        /**
         * The once-only sequence is the frame and the repeat sequence is what a
         * held button sends. Only the once-only part becomes the pulse train,
         * because a caller decides repetition through [IrCode.repeats].
         */
        val pulses = (0 until onceCount * 2).map { index ->
            val cycles = pairs[index]
            (cycles * cycleMicros).toInt().coerceAtLeast(1)
        }
        if (pulses.isEmpty()) {
            return IrParseResult(emptyList(), listOf("$source: Pronto code has no once-only bursts."))
        }

        codes.add(
            IrCode(
                button = familyName,
                protocol = IrProtocolName.RAW,
                rawPulses = pulses.toIntArray(),
                carrierHz = carrierHz,
                // A repeat sequence in the file means the device expects the frame
                // resumed, so sending it twice is what the source states.
                repeats = if (repeatCount > 0) 2 else 1,
                source = source,
            ),
        )
        return IrParseResult(
            listOf(IrDeviceFamily(name = familyName, source = source, codes = codes)),
            problems,
        )
    }

    /** The carrier a Pronto frequency word means, exposed so a test can pin it. */
    fun carrierFor(frequencyWord: Int): Int =
        if (frequencyWord <= 0) 0 else (1_000_000.0 / (frequencyWord * PRONTO_CONSTANT)).toInt()
}

/**
 * Turn a code from any source into the pulse train to transmit.
 *
 * THE ONE PLACE THAT DECIDES, so the emitter cannot invent its own rules. A code
 * with raw pulses is transmitted exactly as recorded; a code with a protocol is
 * synthesised by [IrEncoder]; a code with neither is refused with a reason.
 */
object IrTransmitPlan {

    data class Plan(val signal: IrSignal?, val reason: String?)

    fun plan(code: IrCode): Plan {
        // A LIRC remote's own timing specification decides the pulses, so it is
        // consulted before the protocol table. A file that states its timings has
        // said what to send and naming a protocol over the top of it would be a guess.
        code.lircTiming?.let { timing ->
            val signal = IrEncoder.encodeLirc(timing, code.command, code.repeats.coerceAtLeast(1))
            if (signal.pulses.isEmpty()) {
                return Plan(null, "the LIRC timings for '${code.button}' produced no pulses")
            }
            return Plan(signal, null)
        }

        code.rawPulses?.let { pulses ->
            if (pulses.isEmpty()) {
                return Plan(null, "the recorded capture for '${code.button}' is empty")
            }
            // A capture is already a train, but it may begin with a space, and a
            // leading space is not transmittable. It is trimmed rather than
            // silently shifted, because a shifted train is a wrong code.
            val start = pulses.indexOfFirst { it > 0 }
            if (start < 0) return Plan(null, "the recorded capture for '${code.button}' has no pulses")
            val trimmed = pulses.drop(start).toIntArray()
            if (trimmed.size % 2 != 0) {
                // An odd count ends on a mark, which needs a gap to close it.
                return Plan(
                    IrSignal(
                        carrierHz = code.carrierHz,
                        pulses = trimmed + IrFrame.FRAME_CLOSE_GAP,
                        note = "${trimmed.size} recorded pulses at ${code.carrierHz / 1000} kHz, " +
                            "closed with a gap because the capture ended on a mark.",
                    ),
                    null,
                )
            }
            return Plan(
                IrSignal(
                    carrierHz = code.carrierHz,
                    pulses = trimmed,
                    note = "${trimmed.size} recorded pulses at ${code.carrierHz / 1000} kHz.",
                ),
                null,
            )
        }

        val protocol = code.protocol
            ?: return Plan(
                null,
                "'${code.button}' came from a file that states no protocol and carries no timings, " +
                    "so there is nothing to transmit",
            )

        val signal = IrEncoder.encode(
            protocol = protocol,
            address = code.address,
            command = code.command,
            repeats = code.repeats.coerceAtLeast(1),
        ) ?: return Plan(
            null,
            "${protocol.name} codes cannot be synthesised by this build, so '${code.button}' " +
                "was not transmitted",
        )
        return Plan(signal, null)
    }
}
