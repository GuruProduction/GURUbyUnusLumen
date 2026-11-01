// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

/**
 * Read at most [limit] bytes of text from this stream. Never the whole body.
 *
 * THE OUT OF MEMORY ERROR THIS FIXES. Discovery swept every port on every live
 * host, then asked each open port for its banner and its UPnP description. Both
 * of those read the response with `readText()`, which allocates the entire body
 * into one String with no ceiling. A single local port that streams a large
 * payload, which on a home network includes a media server, a NAS or a router's
 * diagnostic page, is therefore enough to fill the heap and kill the process.
 * The crash showed a 288-byte allocation failing with under 3 MB free, which is
 * the signature of an earlier unbounded read having eaten everything.
 *
 * A device description is a few kilobytes and a banner is one line. Reading more
 * than that is wasted memory in the best case and fatal in the worst, so this
 * stops at the limit. The stream is closed by the caller.
 */
fun readTextBounded(stream: java.io.InputStream, limit: Int = 64 * 1024): String {
    val buffer = ByteArray(8 * 1024)
    val collected = java.io.ByteArrayOutputStream(minOf(limit, buffer.size))
    var total = 0
    while (total < limit) {
        val want = minOf(buffer.size, limit - total)
        val read = stream.read(buffer, 0, want)
        if (read <= 0) break
        collected.write(buffer, 0, read)
        total += read
    }
    return collected.toString(Charsets.UTF_8.name())
}
