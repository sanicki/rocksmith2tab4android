package com.rocksmithtab.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.rocksmithtab.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Single-screen UI.
 *
 * Layout: activity_main.xml
 *
 * The Activity only handles Android lifecycle events and user interaction.
 * All business logic lives in [MainViewModel].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // ── SAF file picker ───────────────────────────────────────────────────

    private val pickPsarc = registerForActivityResult(
        ActivityResultContracts.OpenDocument() // Better for Scoped Storage
    ) { uri: Uri? ->
        if (uri != null) {
            // Grant long-term access for the background thread
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.startConversion(uri)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        observeViewModel()

        // Handle "open with" intent (user tapped a .psarc file in a file manager)
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { viewModel.startConversion(it) }
        }
    }

    // ── UI setup ──────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnOpen.setOnClickListener {
            pickPsarc.launch(arrayOf("*/*")) 
        }
        binding.btnOpenOutput.setOnClickListener {
            viewModel.openOutputFile(this)
        }
    }

    // ── ViewModel observation ─────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: ConversionUiState) {
        when (state) {
            is ConversionUiState.Idle -> {
                binding.progressBar.visibility    = View.GONE
                binding.tvStatus.text             = getString(com.rocksmithtab.R.string.status_idle)
                binding.tvStatus.visibility       = View.VISIBLE
                binding.btnOpenOutput.visibility  = View.GONE
                binding.btnOpenFile.isEnabled     = true
            }

            is ConversionUiState.Converting -> {
                binding.progressBar.visibility    = View.VISIBLE
                binding.progressBar.progress      = state.percent
                binding.tvStatus.text             = state.message
                binding.tvStatus.visibility       = View.VISIBLE
                binding.btnOpenOutput.visibility  = View.GONE
                binding.btnOpenFile.isEnabled     = false
            }

            is ConversionUiState.Success -> {
                binding.progressBar.visibility    = View.GONE
                binding.tvStatus.text             = getString(
                    com.rocksmithtab.R.string.status_success,
                    state.trackCount,
                    state.outputFileName
                )
                binding.tvStatus.visibility       = View.VISIBLE
                binding.btnOpenOutput.visibility  = View.VISIBLE
                binding.btnOpenFile.isEnabled     = true
            }

            is ConversionUiState.Error -> {
                binding.progressBar.visibility    = View.GONE
                binding.tvStatus.text             = getString(com.rocksmithtab.R.string.status_idle)
                binding.tvStatus.visibility       = View.VISIBLE
                binding.btnOpenOutput.visibility  = View.GONE
                binding.btnOpenFile.isEnabled     = true
                Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }
}
