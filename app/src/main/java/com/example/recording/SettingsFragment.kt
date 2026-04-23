package com.example.recording

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.recording.AppPrefs.DeviceBrand
import com.example.recording.service.CallRecorderService
import com.example.recording.service.ServiceController
import com.example.recording.watcher.NativeRecordingWatcher

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var spinnerBrand       : Spinner
    private lateinit var layoutCustomFolder : LinearLayout
    private lateinit var editCustomFolder   : EditText
    private lateinit var textFolderHint     : TextView
    private lateinit var switchForceSpeaker : Switch
    private lateinit var switchDelete       : Switch

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        spinnerBrand       = view.findViewById(R.id.spinnerDeviceBrand)
        layoutCustomFolder = view.findViewById(R.id.layoutCustomFolder)
        editCustomFolder   = view.findViewById(R.id.editCustomFolder)
        textFolderHint     = view.findViewById(R.id.textFolderHint)
        switchForceSpeaker = view.findViewById(R.id.switchForceSpeaker)
        switchDelete       = view.findViewById(R.id.switchDeleteAfterUpload)

        val ctx = requireContext()

        // ── Device brand spinner ─────────────────────────────────────────────
        val adapter = ArrayAdapter(
            ctx,
            android.R.layout.simple_spinner_item,
            DeviceBrand.labels()
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerBrand.adapter = adapter
        spinnerBrand.setSelection(AppPrefs.getDeviceBrand(ctx).ordinal)

        spinnerBrand.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val brand = DeviceBrand.values()[pos]
                AppPrefs.setDeviceBrand(ctx, brand)
                updateFolderHint(brand)
                layoutCustomFolder.visibility =
                    if (brand == DeviceBrand.CUSTOM) View.VISIBLE else View.GONE
                restartWatcher()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // ── Custom folder input ──────────────────────────────────────────────
        editCustomFolder.setText(AppPrefs.getCustomFolder(ctx))
        editCustomFolder.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val path = editCustomFolder.text?.toString() ?: ""
                AppPrefs.setCustomFolder(ctx, path)
                restartWatcher()
            }
        }

        // Initial state
        val currentBrand = AppPrefs.getDeviceBrand(ctx)
        layoutCustomFolder.visibility =
            if (currentBrand == DeviceBrand.CUSTOM) View.VISIBLE else View.GONE
        updateFolderHint(currentBrand)

        // ── Recording toggles ────────────────────────────────────────────────
        switchForceSpeaker.isChecked = AppPrefs.isForceSpeakerphone(ctx)
        switchForceSpeaker.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setForceSpeakerphone(ctx, checked)
        }

        switchDelete.isChecked = AppPrefs.isDeleteAfterUpload(ctx)
        switchDelete.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setDeleteAfterUpload(ctx, checked)
        }
    }

    private fun updateFolderHint(brand: DeviceBrand) {
        val hint = when (brand) {
            DeviceBrand.AUTO ->
                "Watching all known folders automatically."
            DeviceBrand.XIAOMI_REDMI_POCO ->
                "Folders:\n• MIUI/sound_recorder/call_rec\n• Recordings/Call Recording"
            DeviceBrand.VIVO ->
                "Folders:\n• Record/Call\n• Sounds/CallRecord"
            DeviceBrand.ONEPLUS_OPPO ->
                "Folders:\n• CallRecording\n• Recordings/CallRecording"
            DeviceBrand.CUSTOM ->
                "Enter the full path to your OEM's call recording folder."
        }
        textFolderHint.text = hint
    }

    /** Tell the running service to restart its file watchers with the new config. */
    private fun restartWatcher() {
        val ctx = requireContext()
        if (CallRecorderService.isRunning.value) {
            ServiceController.restartWatcher(ctx)
        }
    }
}
