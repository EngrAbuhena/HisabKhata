package com.hisabkhata.ui.transfer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisabkhata.data.transfer.DataTransferRepository
import com.hisabkhata.data.transfer.FileTransfer
import com.hisabkhata.data.transfer.ImportResult
import com.hisabkhata.domain.interchange.CsvTableCodec
import com.hisabkhata.domain.interchange.ImportKind
import com.hisabkhata.domain.interchange.ImportParser
import com.hisabkhata.domain.interchange.ImportPreview
import com.hisabkhata.domain.interchange.MissingImportColumnsException
import com.hisabkhata.domain.interchange.XlsxTableCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SampleFile(val asset: String) { CASH_CSV("cash_sample.csv"), CASH_EXCEL("cash_sample.xlsx"), LOAN_CSV("loan_sample.csv"), LOAN_EXCEL("loan_sample.xlsx") }
enum class ImportProblem { INVALID_FILE, MISSING_COLUMNS, FAILED, SAMPLE_FAILED }
data class ImportUiState(
    val busy: Boolean = false,
    val preview: ImportPreview? = null,
    val skipDuplicates: Boolean = true,
    val createCash: Boolean = false,
    val problem: ImportProblem? = null,
    val sampleSaved: Boolean = false,
    val result: ImportResult? = null
)

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val repository: DataTransferRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val mutable = MutableStateFlow(ImportUiState())
    val state = mutable.asStateFlow()

    fun load(uri: Uri, kind: ImportKind) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, problem = null, sampleSaved = false) }
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val bytes = FileTransfer.read(context, uri)
                    val excel = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()
                    val table = if (excel) XlsxTableCodec.read(bytes, context.cacheDir) else CsvTableCodec.read(bytes)
                    ImportParser.parse(kind, table)
                }
                val preview = repository.markDuplicates(parsed)
                mutable.value = ImportUiState(preview = preview) // cash link defaults off every time
            } catch (e: CancellationException) { throw e }
            catch (_: MissingImportColumnsException) { mutable.update { it.copy(busy = false, problem = ImportProblem.MISSING_COLUMNS) } }
            catch (_: Exception) { mutable.update { it.copy(busy = false, problem = ImportProblem.INVALID_FILE) } }
        }
    }
    fun setSkip(value: Boolean) = mutable.update { it.copy(skipDuplicates = value) }
    fun setCreateCash(value: Boolean) = mutable.update { it.copy(createCash = value) }
    fun closePreview() { if (!mutable.value.busy) mutable.value = ImportUiState() }
    fun dismissResult() { mutable.value = ImportUiState() }
    fun importRows() {
        val state = mutable.value; val preview = state.preview ?: return
        if (state.busy) return
        mutable.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            try {
                val result = repository.importValid(preview, state.skipDuplicates, state.createCash)
                mutable.update { it.copy(busy = false, result = result) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(busy = false, problem = ImportProblem.FAILED) } }
        }
    }
    fun saveSample(uri: Uri, sample: SampleFile) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, problem = null, sampleSaved = false) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { FileTransfer.write(context, uri, context.assets.open("samples/${sample.asset}").use { it.readBytes() }) }
                mutable.update { it.copy(busy = false, sampleSaved = true) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(busy = false, problem = ImportProblem.SAMPLE_FAILED) } }
        }
    }
}
