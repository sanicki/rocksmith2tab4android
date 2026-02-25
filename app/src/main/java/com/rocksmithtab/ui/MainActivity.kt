package com.rocksmithtab.ui

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
import com.rocksmithtab.R
import com.rocksmithtab.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val pickPsarc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            viewModel.startConversion(it) 
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupButtons()
        observeViewModel()
    }

    private fun setupButtons() {
        binding.btnOpenFile.setOnClickListener { pickPsarc.launch(arrayOf("*/*")) }
        binding.btnOpenOutput.setOnClickListener { viewModel.openOutputFile(this) }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { renderState(it) }
            }
        }
    }

    private fun renderState(state: ConversionUiState) {
        when (state) {
            is ConversionUiState.Idle -> {
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = getString(R.string.status_idle)
                binding.btnOpenFile.isEnabled = true
                binding.btnOpenOutput.visibility = View.GONE
            }
            is ConversionUiState.Converting -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.progress = state.percent
                binding.tvStatus.text = state.message
                binding.btnOpenFile.isEnabled = false
                binding.btnOpenOutput.visibility = View.GONE
            }
            is ConversionUiState.Success -> {
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = getString(R.string.status_success, state.trackCount, state.outputFileName)
                binding.btnOpenFile.isEnabled = true
                binding.btnOpenOutput.visibility = View.VISIBLE
            }
            is ConversionUiState.Error -> {
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = getString(R.string.status_idle)
                binding.btnOpenFile.isEnabled = true
                Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }
}
