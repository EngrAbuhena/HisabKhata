package com.hisabkhata.data.transfer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Printable notebook-style ruled table. Long descriptions continue on additional pages. */
object PdfLedgerWriter {
    fun write(table: ExportTable): ByteArray {
        val pdf = PdfDocument(); val out = ByteArrayOutputStream()
        val width = 595; val height = 842; val left = 30f; val right = 565f
        val widths = floatArrayOf(75f, 90f, 90f, 95f, 185f)
        val x = FloatArray(6); x[0] = left; for (i in widths.indices) x[i + 1] = x[i] + widths[i]
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 40, 34); textSize = 9f }
        val title = Paint(text).apply { textSize = 17f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val heading = Paint(text).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val line = Paint().apply { color = Color.rgb(155, 183, 174); strokeWidth = 0.6f }
        val fill = Paint().apply { color = Color.rgb(219, 238, 229) }
        var pageNo = 0; var page: PdfDocument.Page? = null; var canvas: Canvas? = null; var y = 0f
        fun drawTextClipped(c: Canvas, value: String, l: Float, r: Float, baseline: Float, alignRight: Boolean = false, paint: Paint = text) {
            c.save(); c.clipRect(l + 2, baseline - 14, r - 2, baseline + 5)
            val original = paint.textSize
            val maxWidth = r - l - 8
            if (paint.measureText(value) > maxWidth) paint.textSize = max(5f, original * maxWidth / paint.measureText(value))
            c.drawText(value, if (alignRight) r - 4 - paint.measureText(value) else l + 4, baseline, paint)
            paint.textSize = original; c.restore()
        }
        fun startPage() {
            page?.let { pdf.finishPage(it) }
            pageNo++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, pageNo).create())
            val c = page!!.canvas; canvas = c
            drawTextClipped(c, table.title, left, right, 50f, paint = title)
            if (table.subtitle.isNotBlank()) drawTextClipped(c, table.subtitle, left, right, 69f)
            y = 84f
            c.drawRect(left, y, right, y + 25f, fill)
            table.headers.forEachIndexed { i, s -> drawTextClipped(c, s, x[i], x[i + 1], y + 16f, paint = heading) }
            c.drawLine(left, y, right, y, line); c.drawLine(left, y + 25f, right, y + 25f, line)
            x.forEach { c.drawLine(it, y, it, y + 25f, line) }
            y += 25f
        }
        fun wrap(value: String, maxWidth: Float): List<String> {
            if (value.isEmpty()) return listOf("")
            val lines = mutableListOf<String>()
            value.split('\n').forEach { paragraph ->
                var rest = paragraph
                if (rest.isEmpty()) lines += ""
                while (rest.isNotEmpty()) {
                    var count = text.breakText(rest, true, maxWidth, null).coerceAtLeast(1)
                    if (count < rest.length) {
                        val space = rest.lastIndexOf(' ', count - 1)
                        if (space > 0) count = space
                    }
                    lines += rest.substring(0, count)
                    rest = rest.substring(count).trimStart()
                }
            }
            return lines
        }
        try {
            startPage()
            table.rows.forEach { row ->
                val description = wrap(row.lastOrNull()?.text.orEmpty(), widths.last() - 10)
                var offset = 0
                do {
                    if (y + 28f > 785f) startPage()
                    val linesFit = max(1, ((785f - y - 8f) / 12f).toInt())
                    val count = minOf(description.size - offset, linesFit)
                    val rowHeight = max(24f, count * 12f + 8f)
                    val c = canvas!!
                    if (offset == 0) row.take(4).forEachIndexed { i, cell -> drawTextClipped(c, cell.text, x[i], x[i + 1], y + 16f, alignRight = i in 1..3) }
                    repeat(count) { n -> drawTextClipped(c, description[offset + n], x[4], x[5], y + 16f + n * 12f) }
                    c.drawLine(left, y + rowHeight, right, y + rowHeight, line)
                    x.forEach { c.drawLine(it, y, it, y + rowHeight, line) }
                    y += rowHeight; offset += count
                } while (offset < description.size)
            }
            if (y + table.footer.size * 16f + 20 > 810f) startPage()
            y += 20f
            table.footer.forEach { item -> drawTextClipped(canvas!!, item, left, right, y, paint = heading); y += 16f }
            page?.let { pdf.finishPage(it) }; page = null
            pdf.writeTo(out)
        } finally { pdf.close() }
        return out.toByteArray()
    }
}
