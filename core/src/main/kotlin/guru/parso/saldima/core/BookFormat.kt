// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

enum class BookFormat { SQLITE, XML }

class BookFormatException(message: String) : Exception(message)

/** GnuCash 5.x sniffs the backend from the bytes; the extension lies. */
object BookFiles {
    private val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    fun detect(file: File): BookFormat {
        val prefix = FileInputStream(file).use { it.readNBytes(4096) }
        if (prefix.isEmpty()) throw BookFormatException("empty book")
        if (prefix.size >= sqliteHeader.size &&
            prefix.copyOfRange(0, sqliteHeader.size).contentEquals(sqliteHeader)
        ) {
            return BookFormat.SQLITE
        }
        if (prefix.size >= 2 && prefix[0] == 0x1f.toByte() && prefix[1] == 0x8b.toByte()) {
            val head = try {
                GZIPInputStream(ByteArrayInputStream(prefix)).use { it.readNBytes(8) }
            } catch (error: Exception) {
                throw BookFormatException("corrupt gzip book")
            }
            val text = String(head, Charsets.UTF_8)
            if (text.startsWith("<")) return BookFormat.XML
            throw BookFormatException("gzip is not XML")
        }
        val text = String(prefix, Charsets.UTF_8)
        if (text.startsWith("<?xml") || text.startsWith("<gnc-v2")) return BookFormat.XML
        throw BookFormatException("unknown book format")
    }

    /** Opens XML, transparently inflating GnuCash's default gzip. */
    fun xmlStream(file: File): InputStream {
        val raw = BufferedInputStream(FileInputStream(file))
        raw.mark(2)
        val first = raw.read()
        val second = raw.read()
        raw.reset()
        return if (first == 0x1f && second == 0x8b) GZIPInputStream(raw) else raw
    }
}
