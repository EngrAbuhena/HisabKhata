package com.hisabkhata.domain.interchange

import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.local.entity.LoanEntryEntity
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.domain.MoneyInputParser
import com.hisabkhata.domain.MoneyInputResult
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

enum class ImportKind { CASH, LOAN }
enum class ImportIssue { MISSING_PERSON, INVALID_PERSON_TYPE, INVALID_DATE, INVALID_AMOUNT, AMOUNT_REQUIRED }
data class ImportRecord(
    val date: Long, val first: Long, val second: Long, val description: String,
    val personName: String = "", val personType: PersonType? = null
)
data class ImportLine(val sourceRow: Int, val cells: List<String>, val record: ImportRecord?, val issues: List<ImportIssue>, val duplicate: Boolean = false)
data class ImportPreview(val kind: ImportKind, val lines: List<ImportLine>)
class MissingImportColumnsException : IllegalArgumentException()

/** Detects headers instead of relying on column order. Existing Total/Due columns are ignored. */
object ImportParser {
    private fun normalized(s: String) = s.trim().lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    private val dateA = Regex("(\\d{2})\\.(\\d{2})\\.(\\d{2})")
    private val dateB = Regex("(\\d{2})\\.(\\d{2})\\.(\\d{4})")
    private val dateC = Regex("(\\d{2})/(\\d{2})/(\\d{4})")
    private val dateD = Regex("(\\d{4})-(\\d{2})-(\\d{2})")

    fun parse(kind: ImportKind, table: ImportedTable): ImportPreview {
        val head = table.rows.take(20).firstOrNull { row ->
            val h = row.cells.map { normalized(it.text) }.toSet()
            "date" in h && (if (kind == ImportKind.CASH) "deposit" in h || "cost" in h
            else ("personname" in h || "name" in h) && ("persontype" in h || "type" in h) && ("loan" in h || "paid" in h))
        } ?: throw MissingImportColumnsException()
        val headers = head.cells.map { normalized(it.text) }
        fun index(vararg aliases: String): Int = headers.indexOfFirst { it in aliases }
        val dateIndex = index("date"); val first = if (kind == ImportKind.CASH) index("deposit") else index("loan")
        val second = if (kind == ImportKind.CASH) index("cost") else index("paid")
        val description = index("description", "details", "note")
        val name = index("personname", "name"); val type = index("persontype", "type")
        fun cell(row: TableRow, i: Int) = if (i < 0) TableCell("") else row.cells.getOrNull(i) ?: TableCell("")
        val lines = table.rows.dropWhile { it !== head }.drop(1).map { row ->
            val problems = mutableListOf<ImportIssue>()
            val personName = if (kind == ImportKind.LOAN) cell(row, name).text.trim() else ""
            val personType = if (kind == ImportKind.LOAN) when (normalized(cell(row, type).text)) {
                "lent", "ilent" -> PersonType.LENT
                "borrowed", "iborrowed" -> PersonType.BORROWED
                else -> null
            } else null
            if (kind == ImportKind.LOAN && personName.isBlank()) problems += ImportIssue.MISSING_PERSON
            if (kind == ImportKind.LOAN && personType == null) problems += ImportIssue.INVALID_PERSON_TYPE
            val parsedDate = parseDate(cell(row, dateIndex), table.excel1904)
            if (parsedDate == null) problems += ImportIssue.INVALID_DATE
            val amountA = parseAmount(cell(row, first)); val amountB = parseAmount(cell(row, second))
            if (amountA == null || amountB == null) problems += ImportIssue.INVALID_AMOUNT
            else if (amountA == 0L && amountB == 0L) problems += ImportIssue.AMOUNT_REQUIRED
            val record = if (problems.isEmpty()) ImportRecord(parsedDate!!, amountA!!, amountB!!,
                cell(row, description).text.trim(), personName, personType) else null
            ImportLine(row.number, row.cells.map { it.text }, record, problems)
        }
        return ImportPreview(kind, lines)
    }

    fun parseDate(cell: TableCell, excel1904: Boolean = false): Long? {
        val s = cell.text.trim()
        try {
            val date = if (cell.excelNumber && s.isNotEmpty()) {
                val number = BigDecimal(s)
                if (number.signum() < 0 || number.precision() > 20 || number.scale() !in -7..20) return null
                val integer = number.toBigInteger()
                if (integer > java.math.BigInteger.valueOf(3_000_000L)) return null
                val serial = integer.toLong()
                if (!excel1904 && serial == 60L) return null
                if (excel1904) LocalDate.of(1904, 1, 1).plusDays(serial)
                else if (serial < 60) LocalDate.of(1899, 12, 31).plusDays(serial)
                else LocalDate.of(1899, 12, 30).plusDays(serial)
            } else {
                val a = dateA.matchEntire(s); val b = dateB.matchEntire(s)
                val c = dateC.matchEntire(s); val d = dateD.matchEntire(s)
                when {
                    a != null -> LocalDate.of(2000 + a.groupValues[3].toInt(), a.groupValues[2].toInt(), a.groupValues[1].toInt())
                    b != null -> LocalDate.of(b.groupValues[3].toInt(), b.groupValues[2].toInt(), b.groupValues[1].toInt())
                    c != null -> LocalDate.of(c.groupValues[3].toInt(), c.groupValues[2].toInt(), c.groupValues[1].toInt())
                    d != null -> LocalDate.of(d.groupValues[1].toInt(), d.groupValues[2].toInt(), d.groupValues[3].toInt())
                    else -> return null
                }
            }
            return if (date.year in 1..9999) date.toEpochDay() else null
        } catch (_: Exception) { return null }
    }

    private fun parseAmount(cell: TableCell): Long? {
        val raw = cell.text.trim()
        val text = if (cell.excelNumber && raw.isNotEmpty()) try {
            val number = BigDecimal(raw).stripTrailingZeros()
            if (number.precision() > 32 || number.scale() !in -32..32) return null
            number.toPlainString()
        } catch (_: NumberFormatException) { return null } else raw
        return when (val result = MoneyInputParser.parse(text)) {
            MoneyInputResult.Empty -> 0L
            is MoneyInputResult.Amount -> result.paisa
            else -> null
        }
    }
}

/** Duplicate identity includes the person for loans so distinct people's rows are not lost. */
object DuplicatePlanner {
    private data class CashKey(val date: Long, val first: Long, val second: Long, val description: String)
    private data class LoanKey(val name: String, val type: PersonType?, val cash: CashKey)
    private fun name(s: String) = s.trim().lowercase(Locale.ROOT)
    private fun cashKey(r: ImportRecord) = CashKey(r.date, r.first, r.second, r.description)
    private fun loanKey(r: ImportRecord) = LoanKey(name(r.personName), r.personType, cashKey(r))

    fun mark(preview: ImportPreview, people: List<PersonEntity>, loans: List<LoanEntryEntity>, cash: List<CashEntryEntity>): ImportPreview {
        val existingCash = cash.map { CashKey(it.date, it.deposit, it.cost, it.description.trim()) }.toMutableSet()
        val byId = people.associateBy { it.id }
        val existingLoans = loans.mapNotNull { loan -> byId[loan.personId]?.let { person ->
            LoanKey(name(person.name), person.type, CashKey(loan.date, loan.loan, loan.paid, loan.description.trim()))
        } }.toMutableSet()
        return preview.copy(lines = preview.lines.map { line ->
            val r = line.record
            if (r == null) line else line.copy(duplicate = if (preview.kind == ImportKind.CASH) !existingCash.add(cashKey(r)) else !existingLoans.add(loanKey(r)))
        })
    }
}
