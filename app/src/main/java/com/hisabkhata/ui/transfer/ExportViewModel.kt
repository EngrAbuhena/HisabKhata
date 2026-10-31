package com.hisabkhata.ui.transfer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisabkhata.R
import com.hisabkhata.data.repository.LoanRepository
import com.hisabkhata.data.transfer.DataTransferRepository
import com.hisabkhata.data.transfer.ExportData
import com.hisabkhata.data.transfer.ExportFormat
import com.hisabkhata.data.transfer.ExportKind
import com.hisabkhata.data.transfer.ExportRequest
import com.hisabkhata.data.transfer.PdfLedgerWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ExportDelivery { SAVE, SHARE }
data class PreparedExport(val file: File, val suggestedName: String, val format: ExportFormat, val delivery: ExportDelivery)
data class ExportUiState(val busy: Boolean = false, val failed: Boolean = false, val saved: Boolean = false)
sealed interface ExportEvent { data class Ready(val prepared: PreparedExport) : ExportEvent }

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val transfer: DataTransferRepository,
    loans: LoanRepository,
    handle: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel() {
    val people = loans.observePeople().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val initialPerson: Long? = handle["personId"]
    private val mutable = MutableStateFlow(ExportUiState())
    val state = mutable.asStateFlow()
    private val channel = Channel<ExportEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private var pendingSave: PreparedExport? = null

    fun prepare(request: ExportRequest, delivery: ExportDelivery) {
        if (mutable.value.busy) return
        mutable.value = ExportUiState(busy = true)
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val snapshot = transfer.snapshot()
                    val data = ExportData(context, snapshot)
                    val person = request.personId?.let { id -> snapshot.people.firstOrNull { it.id == id } }
                    val bytes = when (request.kind) {
                        ExportKind.CASH -> data.cash(request.from, request.through).let { table -> when (request.format) {
                            ExportFormat.CSV -> data.csv(table)
                            ExportFormat.EXCEL -> data.excel(listOf(context.getString(R.string.nav_cash_book) to table))
                            ExportFormat.PDF -> PdfLedgerWriter.write(table)
                        } }
                        ExportKind.PERSON -> data.singlePerson(requireNotNull(person).id).let { table -> when (request.format) {
                            ExportFormat.CSV -> data.csv(table, includeTitle = true)
                            ExportFormat.EXCEL -> data.excel(listOf(person.name to table))
                            ExportFormat.PDF -> PdfLedgerWriter.write(table)
                        } }
                        ExportKind.ALL_LOANS -> when (request.format) {
                            ExportFormat.CSV -> data.combinedLoansCsv()
                            ExportFormat.EXCEL -> data.excel(data.allPersonTables().map { (p, table) -> p.name to table })
                            ExportFormat.PDF -> throw IllegalArgumentException()
                        }
                    }
                    val base = when (request.kind) {
                        ExportKind.CASH -> context.getString(R.string.export_filename_cash)
                        ExportKind.PERSON -> context.getString(R.string.export_filename_person, requireNotNull(person).name)
                        ExportKind.ALL_LOANS -> context.getString(R.string.export_filename_all_loans)
                    }.map { if (it.isLetterOrDigit() || it in "-_ .") it else '_' }.joinToString("").trim().take(70).ifBlank { context.getString(R.string.app_name) }
                    val name = "$base.${request.format.extension}"
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3L * 24 * 60 * 60 * 1000 }?.forEach { it.delete() }
                    val file = File.createTempFile("export-", ".${request.format.extension}", dir).apply { writeBytes(bytes) }
                    PreparedExport(file, name, request.format, delivery)
                }
                if (delivery == ExportDelivery.SAVE) pendingSave = prepared
                mutable.value = ExportUiState()
                channel.send(ExportEvent.Ready(prepared))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = ExportUiState(failed = true) }
        }
    }
    fun saveTo(uri: Uri?) {
        val prepared = pendingSave ?: return
        pendingSave = null
        if (uri == null) { prepared.file.delete(); return }
        mutable.value = ExportUiState(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot write file")
                    output.use { sink -> prepared.file.inputStream().use { it.copyTo(sink) } }
                    prepared.file.delete()
                }
                mutable.value = ExportUiState(saved = true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = ExportUiState(failed = true) }
        }
    }
    fun shareFailed() { mutable.update { it.copy(failed = true) } }
}
