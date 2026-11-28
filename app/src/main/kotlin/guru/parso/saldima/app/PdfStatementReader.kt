// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

private val pdfBoxInitLock = Any()

@Volatile
private var pdfBoxInitialized = false

/**
 * Extracts every page's text, concatenated in page order, via
 * pdfbox-android's `PDFTextStripper`. The platform's own
 * `android.graphics.pdf.PdfRenderer` only rasterizes pages to bitmaps; it
 * has no text-extraction API, which is why this feature needs pdfbox-android
 * at all. A scanned/image-only PDF yields blank text here, not a crash --
 * `:core`'s `parsePdfStatementText` turns that into the "OCR isn't
 * supported yet" message rather than this function special-casing it.
 */
fun extractPdfText(context: Context, uri: Uri): String {
    if (!pdfBoxInitialized) {
        synchronized(pdfBoxInitLock) {
            if (!pdfBoxInitialized) {
                PDFBoxResourceLoader.init(context.applicationContext)
                pdfBoxInitialized = true
            }
        }
    }
    val input = context.contentResolver.openInputStream(uri) ?: return ""
    return input.use { stream ->
        PDDocument.load(stream).use { document ->
            PDFTextStripper().getText(document)
        }
    }
}
