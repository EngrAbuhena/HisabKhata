package com.hisabkhata.domain.interchange

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.CRC32
import java.io.File
import javax.xml.parsers.SAXParserFactory

class InvalidTableException : IllegalArgumentException()
data class TableCell(val text: String, val excelNumber: Boolean = false)
data class TableRow(val number: Int, val cells: List<TableCell>)
data class ImportedTable(val rows: List<TableRow>, val excel1904: Boolean = false)

/** RFC-4180 CSV, including escaped quotes, BOMs, CRLF and multiline fields. */
object CsvTableCodec {
    private const val MAX_ROWS = 50_001
    private const val MAX_COLS = 80
    fun read(input: ByteArray): ImportedTable {
        if (input.size > 32 * 1024 * 1024) throw InvalidTableException()
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(input)).toString().removePrefix("\uFEFF") }
            catch (_: Exception) { throw InvalidTableException() }
        val rows = mutableListOf<TableRow>(); val cells = mutableListOf<TableCell>(); val value = StringBuilder()
        var quoted = false; var afterQuote = false; var line = 1; var rowStart = 1; var i = 0
        fun field() { if (cells.size >= MAX_COLS) throw InvalidTableException(); cells.add(TableCell(value.toString())); value.setLength(0); afterQuote = false }
        fun row() {
            field()
            if (cells.any { it.text.isNotBlank() }) {
                if (rows.size >= MAX_ROWS) throw InvalidTableException()
                rows.add(TableRow(rowStart, cells.toList()))
            }
            cells.clear()
        }
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { value.append('"'); i++ }
                    c == '"' -> { quoted = false; afterQuote = true }
                    c == '\r' -> { value.append('\n'); if (i + 1 < text.length && text[i + 1] == '\n') i++; line++ }
                    c == '\n' -> { value.append('\n'); line++ }
                    else -> value.append(c)
                }
            } else when {
                c == ',' -> field()
                c == '\r' || c == '\n' -> {
                    row(); if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    line++; rowStart = line
                }
                c == '"' && value.isEmpty() && !afterQuote -> quoted = true
                afterQuote && (c == ' ' || c == '\t') -> Unit
                c == '"' || afterQuote -> throw InvalidTableException()
                else -> value.append(c)
            }
            if (value.length > 1_000_000) throw InvalidTableException()
            i++
        }
        if (quoted) throw InvalidTableException()
        if (cells.isNotEmpty() || value.isNotEmpty() || afterQuote) row()
        return ImportedTable(rows)
    }
    fun write(rows: List<List<String>>): ByteArray = ("\uFEFF" + rows.joinToString("\r\n") { row ->
        row.joinToString(",") { field ->
            if (field.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"${field.replace("\"", "\"\"")}\"" else field
        }
    } + "\r\n").toByteArray(Charsets.UTF_8)
}

/**
 * Lightweight, bounded OOXML reader. Uses Android/JVM SAX rather than desktop-only StAX.
 * Reads the first worksheet, shared/inline strings and cached numeric formula values.
 */
object XlsxTableCodec {
    private const val MAX_UNCOMPRESSED = 64L * 1024 * 1024
    private const val MAX_XML = 32 * 1024 * 1024
    private const val MAX_ROWS = 50_001
    private const val MAX_COLS = 80

    /** Read ZIP central directory from an app-private temporary file; supports ZIP64 descriptors. */
    fun read(input: ByteArray, cacheDir: File? = null): ImportedTable {
        if (input.size > 32 * 1024 * 1024) throw InvalidTableException()
        var temporary: File? = null
        try {
            temporary = File.createTempFile("hisab-import-", ".xlsx", cacheDir)
            temporary.outputStream().use { it.write(input) }
            val entries = mutableMapOf<String, ByteArray>()
            var total = 0L; var entryCount = 0
            ZipFile(temporary).use { zip ->
                val files = zip.entries()
                while (files.hasMoreElements()) {
                    val e = files.nextElement()
                    if (++entryCount > 128) throw InvalidTableException()
                    val name = e.name.replace('\\', '/')
                    val keep = name == "xl/workbook.xml" || name == "xl/_rels/workbook.xml.rels" ||
                        name == "xl/sharedStrings.xml" || (name.startsWith("xl/worksheets/") && name.endsWith(".xml"))
                    val out = if (keep) java.io.ByteArrayOutputStream() else null
                    val buf = ByteArray(8192); var size = 0L; val crc = CRC32()
                    zip.getInputStream(e).use { stream ->
                        while (true) {
                            val n = stream.read(buf); if (n < 0) break
                            total += n; size += n
                            if (total > MAX_UNCOMPRESSED || (keep && size > MAX_XML)) throw InvalidTableException()
                            crc.update(buf, 0, n); out?.write(buf, 0, n)
                        }
                    }
                    if ((e.size >= 0 && e.size != size) || (e.crc >= 0 && e.crc != crc.value)) throw InvalidTableException()
                    if (out != null && entries.put(name, out.toByteArray()) != null) throw InvalidTableException()
                }
            }
            val workbook = entries["xl/workbook.xml"] ?: throw InvalidTableException()
            val meta = WorkbookHandler(); parseXml(workbook, meta)
            val rels = RelationHandler(); entries["xl/_rels/workbook.xml.rels"]?.let { parseXml(it, rels) }
            val target = meta.firstSheetId?.let { rels.targets[it] }
            val sheetPath = if (target == null) "xl/worksheets/sheet1.xml" else {
                val clean = target.removePrefix("/").removePrefix("xl/")
                if (clean.split('/').any { it == ".." || it == "." }) throw InvalidTableException()
                "xl/$clean"
            }
            val shared = SharedStringsHandler()
            entries["xl/sharedStrings.xml"]?.let { parseXml(it, shared) }
            val sheet = SheetHandler(shared.values)
            parseXml(entries[sheetPath] ?: throw InvalidTableException(), sheet)
            return ImportedTable(sheet.rows, meta.date1904)
        } catch (e: InvalidTableException) { throw e }
        catch (_: Exception) { throw InvalidTableException() }
        finally { temporary?.delete() }
    }

    private fun parseXml(bytes: ByteArray, handler: DefaultHandler) {
        // Reject DTDs, including UTF-16 XML, and never resolve external entities.
        if (bytes.toString(Charsets.UTF_8).replace("\u0000", "").contains("<!DOCTYPE", ignoreCase = true)) throw InvalidTableException()
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")
            .forEach { try { factory.setFeature(it, false) } catch (_: Exception) { } }
        val reader = factory.newSAXParser().xmlReader
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.contentHandler = handler
        reader.parse(InputSource(ByteArrayInputStream(bytes)))
    }
    private fun name(local: String, q: String) = local.ifEmpty { q.substringAfter(':') }
    private fun attr(a: Attributes, local: String): String? = (0 until a.length).firstOrNull {
        a.getLocalName(it).ifEmpty { a.getQName(it).substringAfter(':') } == local
    }?.let { a.getValue(it) }

    private class WorkbookHandler : DefaultHandler() {
        var firstSheetId: String? = null; var date1904 = false
        override fun startElement(uri: String?, localName: String, qName: String, a: Attributes) {
            when (name(localName, qName)) {
                "workbookPr" -> date1904 = attr(a, "date1904") in listOf("1", "true")
                "sheet" -> if (firstSheetId == null) firstSheetId = attr(a, "id")
            }
        }
    }
    private class RelationHandler : DefaultHandler() {
        val targets = mutableMapOf<String, String>()
        override fun startElement(uri: String?, localName: String, qName: String, a: Attributes) {
            if (name(localName, qName) == "Relationship") {
                val id = attr(a, "Id"); val target = attr(a, "Target")
                if (id != null && target != null && attr(a, "TargetMode") != "External") targets[id] = target
            }
        }
    }
    private class SharedStringsHandler : DefaultHandler() {
        val values = mutableListOf<String>(); private var inItem = false; private var inText = false
        private val text = StringBuilder()
        override fun startElement(uri: String?, localName: String, qName: String, a: Attributes) {
            when (name(localName, qName)) { "si" -> { inItem = true; text.setLength(0) }; "t" -> if (inItem) inText = true }
        }
        override fun characters(ch: CharArray, start: Int, length: Int) { if (inText) text.append(ch, start, length) }
        override fun endElement(uri: String?, localName: String, qName: String) {
            when (name(localName, qName)) {
                "t" -> inText = false
                "si" -> { if (values.size >= MAX_ROWS * 16 || text.length > 1_000_000) throw InvalidTableException(); values.add(text.toString()); inItem = false }
            }
        }
    }
    private class SheetHandler(private val strings: List<String>) : DefaultHandler() {
        val rows = mutableListOf<TableRow>(); private var rowNumber = 0; private val cells = sortedMapOf<Int, TableCell>()
        private var col = 0; private var type = ""; private var inValue = false; private val value = StringBuilder()
        override fun startElement(uri: String?, localName: String, qName: String, a: Attributes) {
            when (name(localName, qName)) {
                "row" -> {
                    val next = attr(a, "r")?.toIntOrNull() ?: rowNumber + 1
                    if (next <= rowNumber || next > 1_048_576) throw InvalidTableException()
                    rowNumber = next; cells.clear(); col = 0
                }
                "c" -> {
                    val ref = attr(a, "r"); if (ref != null) col = column(ref)
                    if (col !in 0 until MAX_COLS) throw InvalidTableException()
                    type = attr(a, "t") ?: "n"; value.setLength(0)
                }
                "v", "t" -> inValue = true
            }
        }
        override fun characters(ch: CharArray, start: Int, length: Int) { if (inValue) value.append(ch, start, length) }
        override fun endElement(uri: String?, localName: String, qName: String) {
            when (name(localName, qName)) {
                "v", "t" -> inValue = false
                "c" -> {
                    val raw = value.toString()
                    val v = when (type) { "s" -> raw.toIntOrNull()?.let { strings.getOrNull(it) } ?: throw InvalidTableException(); "e" -> ""; else -> raw }
                    cells[col] = TableCell(v, type == "n"); col++
                }
                "row" -> {
                    if (cells.values.any { it.text.isNotBlank() }) {
                        if (rows.size >= MAX_ROWS || rowNumber <= 0) throw InvalidTableException()
                        val last = cells.keys.lastOrNull() ?: -1
                        rows.add(TableRow(rowNumber, (0..last).map { cells[it] ?: TableCell("") }))
                    }
                }
            }
        }
        private fun column(ref: String): Int {
            val letters = ref.takeWhile { it.isLetter() }.uppercase(Locale.ROOT)
            if (letters.isEmpty() || letters.length > 3) throw InvalidTableException()
            var n = 0
            letters.forEach { if (it !in 'A'..'Z') throw InvalidTableException(); n = n * 26 + (it - 'A' + 1) }
            return n - 1
        }
    }
}
