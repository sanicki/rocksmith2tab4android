package com.rocksmithtab.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rocksmithtab.conversion.Converter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** UI states emitted by [MainViewModel]. */
sealed class ConversionUiState {
    object Idle : ConversionUiState()
    data class Converting(val message: String, val percent: Int) : ConversionUiState()
    data class Success(val trackCount: Int, val outputFileName: String, val outputUri: Uri) : ConversionUiState()
    data class Error(val message: String) : ConversionUiState()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow<ConversionUiState>(ConversionUiState.Idle)
    val uiState: StateFlow<ConversionUiState> = _uiState.asStateFlow()

    private var lastOutputUri: Uri? = null

    fun startConversion(inputUri: Uri) {
        if (_uiState.value is ConversionUiState.Converting) return
        viewModelScope.launch { convert(inputUri) }
    }

    fun openOutputFile(activity: Activity) {
        val uri = lastOutputUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            _uiState.value = ConversionUiState.Error("No app found that can open .gpx files.")
        }
    }

    private suspend fun convert(inputUri: Uri) {
        _uiState.value = ConversionUiState.Converting("Opening file…", 0)
        withContext(Dispatchers.IO) {
            try {
                val context    = getApplication<Application>()
                val tempInput  = copyUriToTemp(inputUri)
                _uiState.value = ConversionUiState.Converting("Parsing PSARC…", 10)
                val outputFile = File(context.cacheDir, tempInput.nameWithoutExtension + ".gpx")

                val result = Converter.convert(
                    psarcPath  = tempInput.absolutePath,
                    gpxPath    = outputFile.absolutePath,
                    onProgress = { msg, pct ->
                        _uiState.value = ConversionUiState.Converting(msg, pct)
                    }
                )

                tempInput.delete()
                lastOutputUri = Uri.fromFile(outputFile)

                withContext(Dispatchers.Main) {
                    _uiState.value = ConversionUiState.Success(
                        trackCount     = result.score.tracks.size,
                        outputFileName = outputFile.name,
                        outputUri      = Uri.fromFile(outputFile)
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = ConversionUiState.Error(e.message ?: "An unexpected error occurred.")
                }
            }
        }
    }

    private fun copyUriToTemp(uri: Uri): File {
        val context = getApplication<Application>()
        val name    = DocumentFile.fromSingleUri(context, uri)?.name ?: "input.psarc"
        val temp    = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            temp.outputStream().use { input.copyTo(it) }
        }
        return temp
    }
}
