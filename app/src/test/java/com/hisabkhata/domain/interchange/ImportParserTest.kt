package com.hisabkhata.domain.interchange

import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.local.entity.PersonType
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportParserTest {
    @Test fun bengaliHeadersDigitsAndLoanTypesImportAlongsideEnglish() {
        val cashRows = listOf(
            listOf("তারিখ", "জমা", "খরচ", "বিবরণ"),
            listOf("৩০.১০.২০২৬", "১,২৩,৪৫৬.৭৮", "৫০", "বাংলা বিবরণ")
        )
        val cash = ImportParser.parse(ImportKind.CASH, CsvTableCodec.read(CsvTableCodec.write(cashRows))).lines.single().record!!
        assertEquals(LocalDate.of(2026, 10, 30).toEpochDay(), cash.date)
        assertEquals(12_345_678L, cash.first); assertEquals(5_000L, cash.second)
        assertEquals("বাংলা বিবরণ", cash.description)

        val decomposed = java.text.Normalizer.normalize("ব্যক্তির ধরন", java.text.Normalizer.Form.NFD)
        val loanRows = listOf(
            listOf("ব্যক্তির নাম", decomposed, "তারিখ", "ঋণ", "পরিশোধ", "বিবরণ"),
            listOf("রাহিম", "আমি ধার নিয়েছি", "30.10.26", "500", "50", "পরীক্ষা")
        )
        val loan = ImportParser.parse(ImportKind.LOAN, CsvTableCodec.read(CsvTableCodec.write(loanRows))).lines.single().record!!
        assertEquals(PersonType.BORROWED, loan.personType)
        assertEquals("রাহিম", loan.personName)
        assertEquals(50_000L, loan.first); assertEquals(5_000L, loan.second)
        assertNull(ImportParser.parseDate(TableCell("৩১.০২.২০২৬")))
    }

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    @Test fun acceptsAllRequestedDateFormatsAndRejectsImpossibleDates() {
        listOf("20.01.26", "20.01.2026", "20/01/2026", "2026-01-20").forEach {
            assertEquals(day(2026, 1, 20), ImportParser.parseDate(TableCell(it)))
        }
        assertNull(ImportParser.parseDate(TableCell("31.02.2026")))
        assertNull(ImportParser.parseDate(TableCell("2026-13-20")))
        assertNull(ImportParser.parseDate(TableCell("1/1/2026")))
    }

    @Test fun csvDetectsReorderedColumnsQuotedAmountsAndReportsBadRows() {
        val csv = "\uFEFFDescription,Total,Cost,Date,Deposit\r\n\"Example Person A, by account\",123,20000,26.01.26,\"50,000.50\"\r\nBad,0,1,31.02.26,\r\nZero,0,0,30.01.26,0\r\n"
        val p = ImportParser.parse(ImportKind.CASH, CsvTableCodec.read(csv.toByteArray()))
        assertEquals(3, p.lines.size)
        val valid = p.lines[0].record!!
        assertEquals(5_000_050L, valid.first); assertEquals(2_000_000L, valid.second)
        assertEquals("Example Person A, by account", valid.description); assertEquals(day(2026,1,26), valid.date)
        assertEquals(listOf(ImportIssue.INVALID_DATE), p.lines[1].issues)
        assertEquals(listOf(ImportIssue.AMOUNT_REQUIRED), p.lines[2].issues)
    }

    @Test fun csvRejectsBadAmountsAndSupportsMultilineAndEscapedQuotes() {
        val rows = listOf(listOf("Date", "Deposit", "Cost", "Description"), listOf("20.01.26", "2.345", "", "line one\n\"two\""))
        val parsed = CsvTableCodec.read(CsvTableCodec.write(rows))
        assertEquals(rows[1][3], parsed.rows[1].cells[3].text)
        assertEquals(listOf(ImportIssue.INVALID_AMOUNT), ImportParser.parse(ImportKind.CASH, parsed).lines.single().issues)
    }

    @Test fun missingHeadersAndInvalidLoanTypeAreReported() {
        try { ImportParser.parse(ImportKind.CASH, CsvTableCodec.read("No,headers\n1,2".toByteArray())); fail() }
        catch (_: MissingImportColumnsException) { }
        val csv = "Person Name,Person Type,Date,Loan,Paid,Description\nExample Person A,OTHER,20.01.26,50000,30000,Example transfer\n"
        assertEquals(listOf(ImportIssue.INVALID_PERSON_TYPE), ImportParser.parse(ImportKind.LOAN, CsvTableCodec.read(csv.toByteArray())).lines.single().issues)
    }

    @Test fun duplicateDetectionCoversExistingAndEarlierFileRows() {
        val csv = "Date,Deposit,Cost,Description\n26.01.26,50000,20000,Example Person A by account\n28.01.26,,10000,House rent\n28.01.26,,10000,House rent\n"
        val preview = ImportParser.parse(ImportKind.CASH, CsvTableCodec.read(csv.toByteArray()))
        val existing = listOf(CashEntryEntity(id = 1, date = day(2026,1,26), deposit = 5_000_000, cost = 2_000_000, description = "Example Person A by account", createdAt = 1))
        assertEquals(listOf(true,false,true), DuplicatePlanner.mark(preview, emptyList(), emptyList(), existing).lines.map { it.duplicate })
    }

    @Test fun xlsxParsesInlineSharedAndNumericExcelDatesWithoutFloatingMoney() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            fun entry(name: String, value: String) { out.putNextEntry(ZipEntry(name)); out.write(value.toByteArray()); out.closeEntry() }
            entry("xl/workbook.xml", """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" r:id="r1"/></sheets></workbook>""")
            entry("xl/_rels/workbook.xml.rels", """<Relationships><Relationship Id="r1" Target="worksheets/sheet1.xml"/></Relationships>""")
            entry("xl/sharedStrings.xml", """<sst><si><t>Date</t></si><si><t>Deposit</t></si><si><t>Cost</t></si><si><t>Description</t></si><si><r><t>Example </t></r><r><t>transfer</t></r></si></sst>""")
            entry("xl/worksheets/sheet1.xml", """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row><row r="2"><c r="A2" t="n"><v>46047</v></c><c r="B2" t="n"><v>50000.25</v></c><c r="C2" t="n"><v>30000</v></c><c r="D2" t="s"><v>4</v></c></row></sheetData></worksheet>""")
        }
        val p = ImportParser.parse(ImportKind.CASH, XlsxTableCodec.read(zip.toByteArray()))
        val r = p.lines.single().record!!
        assertEquals(LocalDate.of(1899,12,30).plusDays(46047).toEpochDay(), r.date)
        assertEquals(5_000_025L, r.first); assertEquals(3_000_000L, r.second); assertEquals("Example transfer", r.description)
    }

    @Test fun loanDuplicatesArePerPersonNotJustAmounts() {
        val csv = "Person Name,Person Type,Date,Loan,Paid,Description\nExample Person A,LENT,20.01.26,50000,30000,Example transfer\nExample Person B,LENT,20.01.26,50000,30000,Example transfer\nExample Person A,LENT,20.01.26,50000,30000,Example transfer\n"
        val p = ImportParser.parse(ImportKind.LOAN, CsvTableCodec.read(csv.toByteArray()))
        assertEquals(listOf(false, false, true), DuplicatePlanner.mark(p, emptyList(), emptyList(), emptyList()).lines.map { it.duplicate })
    }

    @Test fun excelDatesSupport1904SystemAndRejectFictitiousLeapDay() {
        assertEquals(day(1904,1,2), ImportParser.parseDate(TableCell("1", excelNumber = true), excel1904 = true))
        assertNull(ImportParser.parseDate(TableCell("60", excelNumber = true)))
        assertNull(ImportParser.parseDate(TableCell("1E+999999999", excelNumber = true)))
    }

    @Test fun commaAmountsSupportWesternAndBangladeshiGrouping() {
        val csv = "Date,Deposit,Cost,Description\n26.01.26,\"1,23,456.78\",\"50,000\",test\n"
        val row = ImportParser.parse(ImportKind.CASH, CsvTableCodec.read(csv.toByteArray())).lines.single().record!!
        assertEquals(12_345_678L, row.first); assertEquals(5_000_000L, row.second)
    }

    @Test fun csvRejectsInvalidUtf8AndTooManyColumns() {
        try { CsvTableCodec.read(byteArrayOf(0xC3.toByte(), 0x28)); fail("bad UTF-8") } catch (_: InvalidTableException) { }
        try { CsvTableCodec.read((List(81) { "x" }.joinToString(",")).toByteArray()); fail("too many columns") } catch (_: InvalidTableException) { }
    }

    @Test fun workbookRowsMustBeOrderedAndUnique() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("xl/workbook.xml")); zip.write("<workbook/>".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write("<worksheet><sheetData><row r=\"2\"/><row r=\"1\"/></sheetData></worksheet>".toByteArray())
            zip.closeEntry()
        }
        try { XlsxTableCodec.read(bytes.toByteArray()); fail("out of order") } catch (_: InvalidTableException) { }
    }

    @Test fun xlsxReaderAcceptsFastExcelZip64StreamingDescriptors() {
        val bytes = ByteArrayOutputStream()
        org.dhatim.fastexcel.Workbook(bytes, "Hisab Khata", "1.0").use { wb ->
            val ws = wb.newWorksheet("Cash Book")
            listOf("Date", "Deposit", "Cost", "Description").forEachIndexed { i, value -> ws.value(0, i, value) }
            ws.value(1, 0, "26.01.26"); ws.value(1, 1, 50000); ws.value(1, 2, 20000); ws.value(1, 3, "Example Person A")
        }
        val row = ImportParser.parse(ImportKind.CASH, XlsxTableCodec.read(bytes.toByteArray())).lines.single().record!!
        assertEquals(5_000_000L, row.first); assertEquals(2_000_000L, row.second)
    }

    @Test fun xlsxBadRowsAndDuplicatesAreReported() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            entry("xl/workbook.xml", "<workbook/>")
            entry("xl/worksheets/sheet1.xml", """<worksheet><sheetData>
              <row r="1"><c r="A1" t="inlineStr"><is><t>Date</t></is></c><c r="B1" t="inlineStr"><is><t>Deposit</t></is></c><c r="C1" t="inlineStr"><is><t>Cost</t></is></c><c r="D1" t="inlineStr"><is><t>Description</t></is></c></row>
              <row r="2"><c r="A2" t="inlineStr"><is><t>20.01.26</t></is></c><c r="B2"><v>50000</v></c></row>
              <row r="3"><c r="A3" t="inlineStr"><is><t>20.01.26</t></is></c><c r="B3"><v>50000</v></c></row>
              <row r="4"><c r="A4" t="inlineStr"><is><t>bad date</t></is></c><c r="B4"><v>50000</v></c></row>
            </sheetData></worksheet>""")
        }
        val p = ImportParser.parse(ImportKind.CASH, XlsxTableCodec.read(bytes.toByteArray()))
        val marked = DuplicatePlanner.mark(p, emptyList(), emptyList(), emptyList())
        assertEquals(listOf(false, true, false), marked.lines.map { it.duplicate })
        assertEquals(listOf(ImportIssue.INVALID_DATE), marked.lines.last().issues)
    }

    @Test fun xlsxSampleLoanParsesPersonAndOptionalFields() {
        val table = ImportedTable(listOf(
            TableRow(1, listOf("Person Name","Person Type","Date","Loan","Paid","Description").map { TableCell(it) }),
            TableRow(2, listOf("Example Person A","LENT","20.01.26","50000","30000","Example transfer").map { TableCell(it) })
        ))
        val r = ImportParser.parse(ImportKind.LOAN, table).lines.single().record!!
        assertEquals("Example Person A", r.personName); assertEquals(PersonType.LENT, r.personType); assertEquals(2_000_000L, r.first-r.second)
    }
}
