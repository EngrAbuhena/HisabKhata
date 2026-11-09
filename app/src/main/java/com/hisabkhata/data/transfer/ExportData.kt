package com.hisabkhata.data.transfer
import com.hisabkhata.util.latinFormat

import android.content.Context
import com.hisabkhata.R
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.domain.CashBalanceCalculator
import com.hisabkhata.domain.CashMovement
import com.hisabkhata.domain.LoanBalanceCalculator
import com.hisabkhata.domain.LoanMovement
import com.hisabkhata.domain.MoneyFormatter
import com.hisabkhata.domain.backup.DatabaseBackup
import com.hisabkhata.domain.interchange.CsvTableCodec
import com.hisabkhata.util.Dates
import org.dhatim.fastexcel.Workbook
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale

data class ExportCell(val text: String, val number: BigDecimal? = null)
data class ExportTable(val title: String, val subtitle: String, val headers: List<String>, val rows: List<List<ExportCell>>, val footer: List<String>)

enum class ExportKind { CASH, PERSON, ALL_LOANS }
enum class ExportFormat(val mime: String, val extension: String) {
    PDF("application/pdf", "pdf"), EXCEL("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"), CSV("text/csv", "csv")
}
data class ExportRequest(val kind: ExportKind, val format: ExportFormat, val personId: Long? = null, val from: Long? = null, val through: Long? = null)

/** Builds tables from an atomic database snapshot; balances are calculated before filtering. */
class ExportData(private val context: Context, private val data: DatabaseBackup) {
    private fun text(id: Int) = context.getString(id)
    private fun amount(value: BigInteger, blankZero: Boolean = false): ExportCell =
        if (blankZero && value.signum() == 0) ExportCell("") else ExportCell(MoneyFormatter.format(value), BigDecimal(value).movePointLeft(2))
    private fun amount(value: Long, blankZero: Boolean = false) = amount(BigInteger.valueOf(value), blankZero)
    private fun footer(label: Int, value: BigInteger) = context.getString(R.string.export_footer_item, text(label), MoneyFormatter.format(value))

    fun cash(from: Long?, through: Long?): ExportTable {
        require(from == null || through == null || from <= through)
        val entries = data.cash.associateBy { it.id }
        val calculation = CashBalanceCalculator().calculate(data.cash.map { CashMovement(it.id, it.date, it.createdAt, it.deposit, it.cost) })
        val filtered = calculation.rows.map { entries.getValue(it.id) to it.balance }.filter { (e, _) ->
            (from == null || e.date >= from) && (through == null || e.date <= through)
        }
        val deposit = filtered.fold(BigInteger.ZERO) { n, pair -> n.add(BigInteger.valueOf(pair.first.deposit)) }
        val cost = filtered.fold(BigInteger.ZERO) { n, pair -> n.add(BigInteger.valueOf(pair.first.cost)) }
        // Closing balance includes earlier rows even when the chosen interval has no entries.
        val closing = calculation.rows.map { entries.getValue(it.id) to it.balance }
            .lastOrNull { (e, _) -> through == null || e.date <= through }?.second ?: BigInteger.ZERO
        val range = if (from != null && through != null) context.getString(R.string.filter_range_summary, Dates.display(from), Dates.display(through)) else text(R.string.export_all_dates)
        return ExportTable(
            text(R.string.export_cash_title), range,
            listOf(R.string.column_date, R.string.column_deposit, R.string.column_cost, R.string.column_total, R.string.column_description).map(::text),
            filtered.map { (e, running) -> listOf(ExportCell(Dates.display(e.date)), amount(e.deposit, true), amount(e.cost, true), amount(running), ExportCell(e.description)) },
            listOf(footer(R.string.total_deposit, deposit), footer(R.string.total_cost, cost), footer(R.string.export_closing_balance, closing))
        )
    }

    fun person(person: PersonEntity): ExportTable {
        val entries = data.loans.filter { it.personId == person.id }.associateBy { it.id }
        val calculation = LoanBalanceCalculator().calculate(entries.values.map { LoanMovement(it.id, it.date, it.createdAt, it.loan, it.paid) })
        return ExportTable(
            context.getString(R.string.person_ledger_title, person.name), "",
            listOf(R.string.column_date, R.string.column_loan, R.string.column_paid, R.string.column_due, R.string.column_description).map(::text),
            calculation.rows.map { row -> entries.getValue(row.id).let { e ->
                listOf(ExportCell(Dates.display(e.date)), amount(e.loan, true), amount(e.paid, true), amount(row.due), ExportCell(e.description))
            } },
            listOf(footer(R.string.total_loan, calculation.totalLoan), footer(R.string.total_paid, calculation.totalPaid), footer(R.string.current_due, calculation.currentDue))
        )
    }

    fun singlePerson(id: Long): ExportTable = person(data.people.firstOrNull { it.id == id } ?: throw IllegalArgumentException())
    fun allPersonTables(): List<Pair<PersonEntity, ExportTable>> = data.people.map { it to person(it) }
    fun combinedLoansCsv(): ByteArray {
        val head = listOf(text(R.string.export_combined_person_name), text(R.string.export_combined_person_type)) +
            listOf(R.string.column_date, R.string.column_loan, R.string.column_paid, R.string.column_due, R.string.column_description).map(::text)
        val rows = allPersonTables().flatMap { (person, table) -> table.rows.map { row ->
            listOf(person.name, text(if (person.type == PersonType.LENT) R.string.export_type_lent else R.string.export_type_borrowed)) + row.map { it.text }
        } }
        return CsvTableCodec.write(listOf(head) + rows)
    }
    fun csv(table: ExportTable, includeTitle: Boolean = false): ByteArray = CsvTableCodec.write(
        (if (includeTitle) listOf(listOf(table.title)) else emptyList()) + listOf(table.headers) + table.rows.map { row -> row.map { it.text } }
    )

    fun excel(tables: List<Pair<String, ExportTable>>): ByteArray {
        val out = ByteArrayOutputStream()
        val names = mutableSetOf<String>()
        val safeTables = if (tables.isEmpty()) listOf(text(R.string.nav_loan_ledger) to ExportTable(
            text(R.string.nav_loan_ledger), "", listOf(R.string.column_date, R.string.column_loan, R.string.column_paid, R.string.column_due, R.string.column_description).map(::text), emptyList(), emptyList()
        )) else tables
        Workbook(out, text(R.string.app_name), "1.0").use { workbook ->
            safeTables.forEachIndexed { index, (name, table) ->
                val sheet = workbook.newWorksheet(uniqueSheetName(name, index, names))
                sheet.value(0, 0, if (table.subtitle.isBlank()) table.title else context.getString(R.string.export_title_with_range, table.title, table.subtitle))
                sheet.range(0, 0, 0, table.headers.lastIndex).merge()
                sheet.style(0, 0).bold().fontSize(14).set()
                table.headers.forEachIndexed { col, value -> sheet.value(1, col, value); sheet.width(col, if (col == table.headers.lastIndex) 34.0 else 18.0) }
                sheet.range(1, 0, 1, table.headers.lastIndex).style().bold().fillColor("C8EFDE").set()
                sheet.freezePane(0, 2)
                table.rows.forEachIndexed { i, row -> row.forEachIndexed { col, cell ->
                    if (cell.number != null && cell.number.precision() <= 15) {
                        sheet.value(i + 2, col, cell.number)
                        sheet.style(i + 2, col).format("#,##0.##").set()
                    } else sheet.value(i + 2, col, cell.text)
                } }
            }
        }
        return out.toByteArray()
    }

    private fun uniqueSheetName(source: String, index: Int, used: MutableSet<String>): String {
        val cleaned = source.map { if (it in "[]:*?/\\" || it.isISOControl()) '_' else it }.joinToString("")
            .trim().trim('\'').take(31).ifBlank { latinFormat(context, R.string.export_sheet_person, index + 1).take(31) }
        var candidate = cleaned; var suffix = 2
        while (!used.add(candidate.lowercase(Locale.ROOT))) {
            val tail = " (${suffix++})"
            candidate = cleaned.take(31 - tail.length) + tail
        }
        return candidate
    }
}
