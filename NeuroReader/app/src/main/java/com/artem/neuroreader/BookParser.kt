package com.artem.neuroreader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile

class BookParser(private val context: Context) {

    data class ParsedBook(val title: String, val text: String)

    fun parse(uri: Uri): ParsedBook {
        val displayName = queryName(uri) ?: "Книга"
        val safeSuffix = displayName.substringAfterLast('.', "bin").take(12)
        val temp = File.createTempFile("neuroreader-", ".$safeSuffix", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Не удалось открыть файл" }
                temp.outputStream().use { output -> input.copyTo(output) }
            }

            val lower = displayName.lowercase()
            val text = when {
                lower.endsWith(".epub") -> parseEpub(temp)
                lower.endsWith(".pdf") -> parsePdf(temp)
                lower.endsWith(".fb2.zip") -> parseFb2Zip(temp)
                lower.endsWith(".fb2") -> parseFb2(readBytesTextAware(temp.readBytes()))
                lower.endsWith(".docx") -> parseDocx(temp)
                lower.endsWith(".rtf") -> parseRtf(readBytesTextAware(temp.readBytes()))
                lower.endsWith(".html") || lower.endsWith(".htm") -> parseHtml(readBytesTextAware(temp.readBytes()))
                lower.endsWith(".txt") || lower.endsWith(".md") -> readBytesTextAware(temp.readBytes())
                lower.endsWith(".zip") -> parseUnknownZip(temp)
                else -> parseFallback(temp)
            }

            val clean = cleanText(text)
            require(clean.length >= 2) { "В книге не найден текст" }
            return ParsedBook(displayName.substringBeforeLast('.', displayName), clean)
        } finally {
            temp.delete()
        }
    }

    private fun queryName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun parsePdf(file: File): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(file).use { document ->
            val text = PDFTextStripper().getText(document)
            require(text.isNotBlank()) {
                "В PDF нет текстового слоя. Для сканированных PDF понадобится OCR."
            }
            return text
        }
    }

    private fun parseFb2(xml: String): String {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val nodes = doc.select("body title p, body subtitle, body p, stanza v")
        val lines = nodes.map { it.text().trim() }.filter { it.isNotBlank() }
        return if (lines.isNotEmpty()) lines.joinToString("\n\n") else doc.text()
    }

    private fun parseFb2Zip(file: File): String = ZipFile(file).use { zip ->
        val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory && it.name.lowercase().endsWith(".fb2") }
            ?: error("В архиве не найден FB2")
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        parseFb2(readBytesTextAware(bytes))
    }

    private fun parseUnknownZip(file: File): String = ZipFile(file).use { zip ->
        val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name.lowercase() }.toList()
        when {
            names.any { it.endsWith(".fb2") } -> {
                val entry = zip.entries().asSequence().first { !it.isDirectory && it.name.lowercase().endsWith(".fb2") }
                parseFb2(readBytesTextAware(zip.getInputStream(entry).use { it.readBytes() }))
            }
            names.contains("word/document.xml") -> parseDocx(file)
            names.contains("meta-inf/container.xml") -> parseEpub(file)
            else -> error("ZIP не похож на EPUB, DOCX или FB2.ZIP")
        }
    }

    private fun parseDocx(file: File): String = ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: error("Некорректный DOCX")
        val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val prepared = xml
            .replace(Regex("<w:tab[^>]*/>"), " NEUROTAB ")
            .replace("</w:p>", " NEUROPARA ")
            .replace("</w:tr>", " NEUROPARA ")
        Jsoup.parse(prepared, "", Parser.xmlParser()).text()
            .replace(" NEUROTAB ", "\t")
            .replace(" NEUROPARA ", "\n\n")
    }

    private fun parseEpub(file: File): String = ZipFile(file).use { zip ->
        val containerEntry = zip.getEntry("META-INF/container.xml")
            ?: zip.entries().asSequence().firstOrNull { it.name.equals("META-INF/container.xml", true) }
            ?: error("Некорректный EPUB: нет container.xml")
        val containerXml = zip.getInputStream(containerEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val containerDoc = Jsoup.parse(containerXml, "", Parser.xmlParser())
        val opfPath = containerDoc.selectFirst("rootfile")?.attr("full-path")
            ?.takeIf { it.isNotBlank() }
            ?: error("Некорректный EPUB: не найден package-файл")

        val opfEntry = zip.getEntry(opfPath) ?: error("Некорректный EPUB: нет $opfPath")
        val opfXml = zip.getInputStream(opfEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val opfDoc = Jsoup.parse(opfXml, "", Parser.xmlParser())
        val manifest = opfDoc.select("manifest item").associate { it.attr("id") to it.attr("href") }
        val spine = opfDoc.select("spine itemref").mapNotNull { manifest[it.attr("idref")] }
        val baseDir = opfPath.substringBeforeLast('/', "")

        val chapters = spine.mapNotNull { href ->
            val path = normalizeZipPath(baseDir, href.substringBefore('#'))
            val entry = zip.getEntry(path) ?: return@mapNotNull null
            val html = zip.getInputStream(entry).use { readBytesTextAware(it.readBytes()) }
            parseHtml(html).takeIf { it.isNotBlank() }
        }

        if (chapters.isNotEmpty()) chapters.joinToString("\n\n\n")
        else {
            val htmlEntries = zip.entries().asSequence().filter {
                !it.isDirectory && (it.name.endsWith(".xhtml", true) || it.name.endsWith(".html", true) || it.name.endsWith(".htm", true))
            }.toList()
            htmlEntries.joinToString("\n\n\n") { entry ->
                parseHtml(zip.getInputStream(entry).use { readBytesTextAware(it.readBytes()) })
            }
        }
    }

    private fun normalizeZipPath(baseDir: String, rawHref: String): String {
        val decoded = Uri.decode(rawHref).replace('\\', '/')
        val all = (if (baseDir.isBlank()) decoded else "$baseDir/$decoded").split('/')
        val stack = ArrayDeque<String>()
        for (part in all) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun parseHtml(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script, style, noscript, svg").remove()
        val blocks = doc.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        return if (blocks.isNotEmpty()) blocks.joinToString("\n\n") else doc.body()?.text().orEmpty()
    }

    private fun parseRtf(rtf: String): String {
        var s = rtf
            .replace(Regex("\\\\par[d]?\\b"), "\n")
            .replace(Regex("\\\\line\\b"), "\n")
            .replace(Regex("\\\\tab\\b"), "\t")

        s = Regex("\\\\u(-?\\d+)\\??").replace(s) { match ->
            val value = match.groupValues[1].toIntOrNull() ?: return@replace ""
            val code = if (value < 0) value + 65536 else value
            code.toChar().toString()
        }

        s = Regex("\\\\'([0-9a-fA-F]{2})").replace(s) { match ->
            val b = match.groupValues[1].toInt(16).toByte()
            String(byteArrayOf(b), Charset.forName("windows-1251"))
        }

        return s
            .replace(Regex("\\{\\\\[^{}]+\\}"), " ")
            .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
            .replace("{", "")
            .replace("}", "")
            .replace("\\\\", "\\")
    }

    private fun parseFallback(file: File): String {
        val bytes = file.readBytes()
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return parseUnknownZip(file)
        }
        val text = readBytesTextAware(bytes)
        val printable = text.take(4000).count { it.isLetterOrDigit() || it.isWhitespace() || it in ".,!?;:—-()[]«»\"'" }
        require(text.isNotEmpty() && printable.toFloat() / text.take(4000).length.coerceAtLeast(1) > 0.55f) {
            "Формат пока не поддерживается"
        }
        return text
    }

    private fun readBytesTextAware(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val probe = String(bytes.take(600).toByteArray(), Charsets.ISO_8859_1).lowercase()
        val charset = when {
            "windows-1251" in probe || "cp1251" in probe -> Charset.forName("windows-1251")
            "utf-16" in probe -> Charsets.UTF_16
            else -> Charsets.UTF_8
        }
        return String(bytes, charset)
    }

    private fun cleanText(input: String): String = input
        .replace('\u0000', ' ')
        .replace('\u00A0', ' ')
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
