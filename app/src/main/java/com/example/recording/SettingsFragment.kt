package com.example.recording

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.recording.AppPrefs.DeviceBrand
import com.example.recording.service.CallRecorderService
import com.example.recording.service.ServiceController

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var spinnerBrand       : Spinner
    private lateinit var layoutCustomFolder : LinearLayout
    private lateinit var editCustomFolder   : EditText
    private lateinit var btnBrowseFolder    : Button
    private lateinit var textFolderHint     : TextView
    private lateinit var switchForceSpeaker : Switch
    private lateinit var switchDelete       : Switch

    // System folder picker launcher
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // Convert content URI → real file path
        val path = uriToPath(uri)
        if (path != null) {
            editCustomFolder.setText(path)
            AppPrefs.setCustomFolder(requireContext(), path)
            restartWatcher()
        } else {
            // Fallback: store the URI string so the user can see what was picked
            val raw = uri.path ?: uri.toString()
            editCustomFolder.setText(raw)
            AppPrefs.setCustomFolder(requireContext(), raw)
            restartWatcher()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Hamburger opens the nav drawer
        view.findViewById<View>(R.id.btnMenu).setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }

        spinnerBrand       = view.findViewById(R.id.spinnerDeviceBrand)
        layoutCustomFolder = view.findViewById(R.id.layoutCustomFolder)
        editCustomFolder   = view.findViewById(R.id.editCustomFolder)
        btnBrowseFolder    = view.findViewById(R.id.btnBrowseFolder)
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

        // ── Custom folder — type or browse ───────────────────────────────────
        editCustomFolder.setText(AppPrefs.getCustomFolder(ctx))
        editCustomFolder.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                AppPrefs.setCustomFolder(ctx, editCustomFolder.text?.toString() ?: "")
                restartWatcher()
            }
        }

        btnBrowseFolder.setOnClickListener {
            // Open Android's built-in folder picker
            val existing = AppPrefs.getCustomFolder(ctx)
            val startUri = if (existing.isNotBlank())
                Uri.parse("file://$existing")
            else null
            folderPickerLauncher.launch(startUri)
        }

        // Initial visibility
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateFolderHint(brand: DeviceBrand) {
        textFolderHint.text = when (brand) {
            DeviceBrand.AUTO ->
                "Watching all known OEM folders automatically."
            DeviceBrand.XIAOMI_REDMI_POCO ->
                "Folders:\n• MIUI/sound_recorder/call_rec\n• Recordings/Call Recording"
            DeviceBrand.VIVO ->
                "Folders:\n• Record/Call\n• Sounds/CallRecord"
            DeviceBrand.ONEPLUS_OPPO ->
                "Folders:\n• CallRecording\n• Recordings/CallRecording"
            DeviceBrand.CUSTOM ->
                "Tap Browse to pick the folder, or type the path manually."
        }
    }

    /**
     * Convert a content:// URI from ACTION_OPEN_DOCUMENT_TREE to a real /storage/… path.
     * Works for primary storage on most devices.
     */
    private fun uriToPath(uri: Uri): String? {
        // content://com.android.externalstorage.documents/tree/primary%3ARecord%2FCall
        val path = uri.path ?: return null
        // path looks like: /tree/primary:Record/Call  or  /tree/0000-1111:DCIM
        val treePrefix = "/tree/"
        if (!path.startsWith(treePrefix)) return null
        val encoded = path.removePrefix(treePrefix)   // e.g. "primary:Record/Call"
        val colon = encoded.indexOf(':')
        if (colon < 0) return null
        val volume = encoded.substring(0, colon)
        val relative = encoded.substring(colon + 1)
        return if (volume.equals("primary", ignoreCase = true)) {
            "/storage/emulated/0/$relative"
        } else {
            "/storage/$volume/$relative"
        }
    }

    private fun restartWatcher() {
        if (CallRecorderService.isRunning.value) {
            ServiceController.restartWatcher(requireContext())
        }
    }
}
