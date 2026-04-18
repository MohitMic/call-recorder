package com.example.recording

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var editUrl: EditText
    private lateinit var editKey: EditText
    private lateinit var btnSave: Button
    private lateinit var switchDelete: Switch
    private lateinit var switchForceSpeaker: Switch
    private lateinit var btnTestConnection: Button
    private lateinit var btnScan: Button
    private lateinit var textScanStatus: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editUrl            = view.findViewById(R.id.editSupabaseUrl)
        editKey            = view.findViewById(R.id.editSupabaseKey)
        btnSave            = view.findViewById(R.id.btnSaveServer)
        switchDelete       = view.findViewById(R.id.switchDeleteAfterUpload)
        switchForceSpeaker = view.findViewById(R.id.switchForceSpeaker)
        btnTestConnection  = view.findViewById(R.id.btnTestConnection)
        btnScan            = view.findViewById(R.id.btnCompatScan)
        textScanStatus     = view.findViewById(R.id.textScanStatus)

        val ctx = requireContext()
        editUrl.setText(AppPrefs.getSupabaseUrl(ctx))
        editKey.setText(AppPrefs.getSupabaseKey(ctx))
        switchDelete.isChecked = AppPrefs.isDeleteAfterUpload(ctx)
        switchForceSpeaker.isChecked = AppPrefs.isForceSpeakerphone(ctx)

        btnSave.setOnClickListener {
            AppPrefs.setSupabaseUrl(ctx, editUrl.text?.toString() ?: "")
            AppPrefs.setSupabaseKey(ctx, editKey.text?.toString() ?: "")
            btnSave.text = "Saved"
            btnSave.postDelayed({ btnSave.text = "Save" }, 2000)
        }

        switchDelete.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setDeleteAfterUpload(ctx, checked)
        }

        switchForceSpeaker.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setForceSpeakerphone(ctx, checked)
        }

        btnTestConnection.setOnClickListener { testConnection() }
        btnScan.setOnClickListener { runCompatScan() }
    }

    private fun testConnection() {
        val url = editUrl.text?.toString()?.trim() ?: ""
        val key = editKey.text?.toString()?.trim() ?: ""

        if (url.isBlank() || key.isBlank()) {
            showDialog("Connection Test", "Please enter Supabase URL and key first.")
            return
        }

        btnTestConnection.isEnabled = false
        btnTestConnection.text = "Testing..."

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // List files in the bucket — simple authenticated GET
                    val testUrl = "${url.trimEnd('/')}/storage/v1/bucket"
                    val client = OkHttpClient()
                    val request = Request.Builder()
                        .url(testUrl)
                        .addHeader("Authorization", "Bearer $key")
                        .addHeader("apikey", key)
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        "${response.code} ${response.message}\n\n" +
                        (response.body?.string()?.take(400) ?: "(empty body)")
                    }
                }.getOrElse { "Error: ${it.message}" }
            }

            btnTestConnection.isEnabled = true
            btnTestConnection.text = "Test Connection"

            val ok = result.startsWith("200") || result.startsWith("2")
            val title = if (ok) "Connection OK" else "Connection FAILED"
            showDialog(title, result)
        }
    }

    private fun showDialog(title: String, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun runCompatScan() {
        btnScan.isEnabled = false
        btnScan.text = getString(R.string.scan_running)
        textScanStatus.visibility = View.VISIBLE
        textScanStatus.text = "Starting scan..."

        viewLifecycleOwner.lifecycleScope.launch {
            val scanner = CompatibilityScanner(requireContext())
            val results = withContext(Dispatchers.IO) {
                scanner.scan { msg ->
                    launch(Dispatchers.Main) { textScanStatus.text = msg }
                }
            }

            btnScan.isEnabled = true
            btnScan.text = getString(R.string.btn_compat_scan)

            val sb = StringBuilder()
            for (r in results) {
                val emoji = when (r.status) {
                    CompatibilityScanner.ScanStatus.WORKS             -> "[OK]"
                    CompatibilityScanner.ScanStatus.ACCESSIBLE_SILENT -> "[SILENT]"
                    CompatibilityScanner.ScanStatus.BLOCKED           -> "[BLOCKED]"
                    CompatibilityScanner.ScanStatus.FAILED            -> "[FAILED]"
                }
                val label = when (r.status) {
                    CompatibilityScanner.ScanStatus.WORKS             -> getString(R.string.scan_works)
                    CompatibilityScanner.ScanStatus.ACCESSIBLE_SILENT -> getString(R.string.scan_silent)
                    CompatibilityScanner.ScanStatus.BLOCKED           -> getString(R.string.scan_blocked)
                    CompatibilityScanner.ScanStatus.FAILED            -> getString(R.string.scan_failed)
                }
                sb.appendLine("$emoji  ${r.sourceName} (${r.sourceValue})")
                sb.appendLine("    $label")
                if (r.fileSizeBytes > 0) sb.appendLine("    ${r.fileSizeBytes} bytes recorded")
                if (r.errorMessage.isNotBlank()) sb.appendLine("    ${r.errorMessage}")
                sb.appendLine()
            }

            sb.appendLine("─────────────────────────────────────")
            sb.appendLine(buildChainSummary(results))

            textScanStatus.text = sb.toString().trimEnd()

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.scan_done_title))
                .setMessage(sb.toString().trimEnd())
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun buildChainSummary(results: List<CompatibilityScanner.SourceResult>): String {
        val workingNames = results
            .filter { it.status == CompatibilityScanner.ScanStatus.WORKS }
            .map { it.sourceName }
            .toSet()

        val chain = listOf(
            "VOICE_CALL", "VOICE_DOWNLINK", "VOICE_UPLINK",
            "VOICE_COMMUNICATION", "VOICE_RECOGNITION", "MIC"
        )
        val effectiveChain = chain.filter { it in workingNames }

        return if (effectiveChain.isEmpty()) {
            "No sources confirmed working. Recording may produce silent files."
        } else {
            val primary = effectiveChain.first()
            val oemNote = if (primary in listOf("VOICE_CALL", "VOICE_DOWNLINK", "VOICE_UPLINK"))
                " (OEM-extended, both call sides)"
            else
                " (mic only)"
            "Primary: $primary$oemNote\nChain: ${effectiveChain.joinToString(" -> ")}"
        }
    }
}
