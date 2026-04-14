package com.example.recording

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.recording.capture.MediaProjectionStore
import com.example.recording.service.CallRecorderService
import com.example.recording.service.ServiceController
import com.example.recording.ui.LiveRecorderStatus

import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

class DashboardFragment : Fragment(R.layout.fragment_dashboard) {

    // Service card
    private lateinit var switchService: Switch
    private lateinit var textServiceSubtitle: TextView

    // Live call card
    private lateinit var cardLiveCall: View
    private lateinit var textLiveDuration: TextView
    private lateinit var textLiveNumber: TextView
    private lateinit var textLiveSource: TextView
    private lateinit var textLiveHealthBanner: TextView

    // Checklist dots + buttons
    private lateinit var dotMic: View
    private lateinit var dotPhoneState: View
    private lateinit var dotNotifications: View
    private lateinit var dotAccessibility: View
    private lateinit var dotDualChannel: View

    private lateinit var btnMic: Button
    private lateinit var btnPhoneState: Button
    private lateinit var btnNotifications: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnDualChannel: Button

    private var updatingSwitch = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateChecklist() }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ServiceController.notifyMediaProjectionGranted(requireContext(), result.resultCode, result.data!!)
            updateChecklist()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        switchService        = view.findViewById(R.id.switchService)
        textServiceSubtitle  = view.findViewById(R.id.textServiceSubtitle)
        cardLiveCall         = view.findViewById(R.id.cardLiveCall)
        textLiveDuration     = view.findViewById(R.id.textLiveDuration)
        textLiveNumber       = view.findViewById(R.id.textLiveNumber)
        textLiveSource       = view.findViewById(R.id.textLiveSource)
        textLiveHealthBanner = view.findViewById(R.id.textLiveHealthBanner)
        dotMic               = view.findViewById(R.id.dotMic)
        dotPhoneState        = view.findViewById(R.id.dotPhoneState)
        dotNotifications     = view.findViewById(R.id.dotNotifications)
        dotAccessibility     = view.findViewById(R.id.dotAccessibility)
        dotDualChannel       = view.findViewById(R.id.dotDualChannel)
        btnMic               = view.findViewById(R.id.btnMic)
        btnPhoneState        = view.findViewById(R.id.btnPhoneState)
        btnNotifications     = view.findViewById(R.id.btnNotifications)
        btnAccessibility     = view.findViewById(R.id.btnAccessibility)
        btnDualChannel       = view.findViewById(R.id.btnDualChannel)

        // Service toggle
        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (!updatingSwitch) {
                if (isChecked) ServiceController.start(requireContext())
                else ServiceController.stop(requireContext())
            }
        }

        // Permission buttons
        btnMic.setOnClickListener { requestRuntimePermissions() }
        btnPhoneState.setOnClickListener { requestRuntimePermissions() }
        btnNotifications.setOnClickListener { requestRuntimePermissions() }
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnDualChannel.setOnClickListener {
            val pm = requireActivity().getSystemService(Activity.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(pm.createScreenCaptureIntent())
        }

        // Observe service running state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallRecorderService.isRunning.collect { running ->
                    updatingSwitch = true
                    switchService.isChecked = running
                    updatingSwitch = false
                    textServiceSubtitle.text = getString(
                        if (running) R.string.service_card_subtitle_on
                        else R.string.service_card_subtitle_off
                    )
                }
            }
        }

        // Observe live status
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallRecorderService.liveStatus.collect { bindLiveCard(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateChecklist()
    }

    // ── Live call card ────────────────────────────────────────────────────────

    private fun bindLiveCard(s: LiveRecorderStatus) {
        if (s.isRecording) {
            cardLiveCall.visibility = View.VISIBLE
            textLiveDuration.text = formatDuration(s.recordingElapsedSeconds)
            textLiveNumber.text = s.currentCallNumber.ifBlank { "Unknown number" }
            textLiveSource.text = buildString {
                append(if (s.appCallState == "ACTIVE") "↓ Incoming" else s.appCallState)
                append("  ·  ")
                append(s.currentAudioSource)
            }
            textLiveHealthBanner.text = s.healthBanner
        } else {
            cardLiveCall.visibility = View.GONE
        }
    }

    private fun formatDuration(seconds: Long): String {
        val m = TimeUnit.SECONDS.toMinutes(seconds)
        val s = seconds - TimeUnit.MINUTES.toSeconds(m)
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    // ── Checklist ─────────────────────────────────────────────────────────────

    private fun updateChecklist() {
        val ctx = requireContext()

        val hasMic = hasPermission(Manifest.permission.RECORD_AUDIO)
        val hasPhone = hasPermission(Manifest.permission.READ_PHONE_STATE)
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else true
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasDualChannel = MediaProjectionStore.projection != null

        applyDot(dotMic,           hasMic)
        applyDot(dotPhoneState,    hasPhone)
        applyDot(dotNotifications, hasNotif)
        applyDot(dotAccessibility, hasAccessibility)
        applyDot(dotDualChannel,   hasDualChannel)

        btnMic.visibility           = if (hasMic)           View.GONE else View.VISIBLE
        btnPhoneState.visibility    = if (hasPhone)         View.GONE else View.VISIBLE
        btnNotifications.visibility = if (hasNotif)         View.GONE else View.VISIBLE
        btnAccessibility.visibility = if (hasAccessibility) View.GONE else View.VISIBLE
        btnDualChannel.visibility   = if (hasDualChannel)   View.GONE else View.VISIBLE
    }

    private fun applyDot(dot: View, granted: Boolean) {
        val color = if (granted) {
            ContextCompat.getColor(requireContext(), R.color.perm_granted)
        } else {
            ContextCompat.getColor(requireContext(), R.color.perm_denied)
        }
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }
        dot.background = drawable
    }

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(requireContext(), perm) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedService = "${requireContext().packageName}/" +
            "com.example.recording.accessibility.CallRecorderAccessibilityService"
        val enabled = android.provider.Settings.Secure.getString(
            requireContext().contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(expectedService, ignoreCase = true) }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}
