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

    fun saveOutputFile(destinationUri: Uri) {
        val sourceUri = lastOutputUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                    context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                // Optional: Notify UI of success via a new State
            } catch (e: Exception) {
                _uiState.value = ConversionUiState.Error("Failed to save file: ${e.message}")
            }
        }
    }

    fun saveLogFile(destinationUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                    outputStream.write(com.rocksmithtab.utils.AppLogger.getLogText().toByteArray())
                }
            } catch (e: Exception) {
                _uiState.value = ConversionUiState.Error("Failed to save log: ${e.message}")
            }
        }
    }

    private suspend fun convert(inputUri: Uri) {
        _uiState.value = ConversionUiState.Converting("Opening file...", 0)
        withContext(Dispatchers.IO) {
            var tempInput: File? = null
            com.rocksmithtab.utils.AppLogger.clear()
            com.rocksmithtab.utils.AppLogger.d("MainViewModel", "Starting conversion...")
            try {
                val context = getApplication<Application>()
                tempInput = copyUriToTemp(inputUri)
                
                val outputFile = File(context.cacheDir, tempInput.nameWithoutExtension + ".gpx")

                // FIX: Use Converter object and correct SAM conversion for progress
                val result = Converter.convert(
                    inputPath = tempInput.absolutePath,
                    outputPath = outputFile.absolutePath,
                    progress = { msg, pct ->
                        _uiState.value = ConversionUiState.Converting(msg, pct)
                    }
                )

                lastOutputUri = Uri.fromFile(outputFile)

                withContext(Dispatchers.Main) {
                    _uiState.value = ConversionUiState.Success(
                        trackCount = result.score.tracks.size,
                        outputFileName = outputFile.name,
                        outputUri = Uri.fromFile(outputFile)
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace() // Log actual error to Logcat
                withContext(Dispatchers.Main) {
                    _uiState.value = ConversionUiState.Error(e.localizedMessage ?: "Conversion failed")
                }
            } finally {
                tempInput?.delete()
            }
        }
    }

    private fun copyUriToTemp(uri: Uri): File {
        val context = getApplication<Application>()
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: "input.psarc"
        val temp = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw Exception("Failed to open input stream")
        return temp
    }
}
