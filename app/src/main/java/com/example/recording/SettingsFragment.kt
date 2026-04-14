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

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var editUrl: EditText
    private lateinit var editKey: EditText
    private lateinit var btnSave: Button
    private lateinit var switchWifi: Switch
    private lateinit var switchDelete: Switch
    private lateinit var btnScan: Button
    private lateinit var textScanStatus: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editUrl        = view.findViewById(R.id.editSupabaseUrl)
        editKey        = view.findViewById(R.id.editSupabaseKey)
        btnSave        = view.findViewById(R.id.btnSaveServer)
        switchWifi     = view.findViewById(R.id.switchWifiOnly)
        switchDelete   = view.findViewById(R.id.switchDeleteAfterUpload)
        btnScan        = view.findViewById(R.id.btnCompatScan)
        textScanStatus = view.findViewById(R.id.textScanStatus)

        // Load saved values
        val ctx = requireContext()
        editUrl.setText(AppPrefs.getSupabaseUrl(ctx))
        editKey.setText(AppPrefs.getSupabaseKey(ctx))
        switchWifi.isChecked   = AppPrefs.isWifiOnly(ctx)
        switchDelete.isChecked = AppPrefs.isDeleteAfterUpload(ctx)

        btnSave.setOnClickListener {
            AppPrefs.setSupabaseUrl(ctx, editUrl.text?.toString() ?: "")
            AppPrefs.setSupabaseKey(ctx, editKey.text?.toString() ?: "")
            btnSave.text = "Saved ✓"
            btnSave.postDelayed({ btnSave.text = "Save" }, 2000)
        }

        switchWifi.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setWifiOnly(ctx, checked)
        }

        switchDelete.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setDeleteAfterUpload(ctx, checked)
        }

        btnScan.setOnClickListener { runCompatScan() }
    }

    private fun runCompatScan() {
        btnScan.isEnabled = false
        btnScan.text = getString(R.string.scan_running)
        textScanStatus.visibility = View.VISIBLE
        textScanStatus.text = "Starting scan…"

        viewLifecycleOwner.lifecycleScope.launch {
            val scanner = CompatibilityScanner(requireContext())
            val results = withContext(Dispatchers.IO) {
                scanner.scan { msg ->
                    launch(Dispatchers.Main) { textScanStatus.text = msg }
                }
            }

            btnScan.isEnabled = true
            btnScan.text = getString(R.string.btn_compat_scan)

            // ── Per-source results ──────────────────────────────────────────
            val sb = StringBuilder()
            for (r in results) {
                val emoji = when (r.status) {
                    CompatibilityScanner.ScanStatus.WORKS             -> "✅"
                    CompatibilityScanner.ScanStatus.ACCESSIBLE_SILENT -> "⚠️"
                    CompatibilityScanner.ScanStatus.BLOCKED           -> "🔴"
                    CompatibilityScanner.ScanStatus.FAILED            -> "❌"
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

            // ── Recommended recorder chain for THIS device ──────────────────
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

    /**
     * Computes a human-readable summary of which recorder source the app will
     * actually use on this device, based on scanner results.
     *
     * Mirrors the priority order in RecorderController:
     *   VOICE_CALL → VOICE_DOWNLINK → VOICE_UPLINK →
     *   VOICE_COMMUNICATION → VOICE_RECOGNITION → MIC
     */
    private fun buildChainSummary(results: List<CompatibilityScanner.SourceResult>): String {
        val workingNames = results
            .filter { it.status == CompatibilityScanner.ScanStatus.WORKS }
            .map { it.sourceName }
            .toSet()

        // Recorder priority order (matches RecorderController)
        val chain = listOf(
            "VOICE_CALL",
            "VOICE_DOWNLINK",
            "VOICE_UPLINK",
            "VOICE_COMMUNICATION",
            "VOICE_RECOGNITION",
            "MIC"
        )

        val effectiveChain = chain.filter { it in workingNames }

        return if (effectiveChain.isEmpty()) {
            "⚠️  No sources confirmed working.\n" +
            "Recording may produce silent files on this device."
        } else {
            val primary = effectiveChain.first()
            val oemBonus = primary in listOf("VOICE_CALL", "VOICE_DOWNLINK", "VOICE_UPLINK")
            val oemNote = if (oemBonus)
                " ← OEM-extended (both call sides possible!)"
            else
                " ← standard (your mic only)"
            "Primary source: $primary$oemNote\n" +
            "Full chain: ${effectiveChain.joinToString(" → ")}"
        }
    }
}
