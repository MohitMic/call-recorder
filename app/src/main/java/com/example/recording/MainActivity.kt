package com.example.recording

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        // Always dark — no day/night toggle
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Schedule the periodic background sweep so recordings reach the
        // admin panel even if the foreground service gets killed by the OEM.
        // ExistingPeriodicWorkPolicy.KEEP — won't replace an existing schedule.
        com.example.recording.upload.PeriodicSweepWorker.schedule(applicationContext)

        // Ask the user (once) to whitelist the app from battery optimization.
        // Without this, OEM aggressive battery savers can still kill the
        // periodic worker. Only one prompt — Android remembers their answer.
        requestBatteryOptimizationExemption()

        drawerLayout = findViewById(R.id.drawerLayout)

        // Default fragment on first create (no back stack entry — it's the root)
        if (savedInstanceState == null) {
            loadFragment(RecordingsFragment(), TAG_RECORDINGS, addToBackStack = false)
        }

        // Drawer items
        findViewById<android.view.View>(R.id.drawerItemRecordings).setOnClickListener {
            // Pop everything back to root then show recordings
            supportFragmentManager.popBackStack(null,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            loadFragment(RecordingsFragment(), TAG_RECORDINGS, addToBackStack = false)
            drawerLayout.closeDrawers()
        }

        findViewById<android.view.View>(R.id.drawerItemDashboard).setOnClickListener {
            loadFragment(DashboardFragment(), TAG_DASHBOARD, addToBackStack = true)
            drawerLayout.closeDrawers()
        }

        findViewById<android.view.View>(R.id.drawerItemSettings).setOnClickListener {
            loadFragment(SettingsFragment(), TAG_SETTINGS, addToBackStack = true)
            drawerLayout.closeDrawers()
        }
    }

    /** Handle back: close drawer first, then pop fragment stack, then exit. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            drawerLayout.isDrawerOpen(GravityCompat.START) ->
                drawerLayout.closeDrawer(GravityCompat.START)
            supportFragmentManager.backStackEntryCount > 0 ->
                supportFragmentManager.popBackStack()
            else -> super.onBackPressed()
        }
    }

    /** Opens the navigation drawer — called from fragment hamburger buttons. */
    fun openDrawer() = drawerLayout.openDrawer(GravityCompat.START)

    /**
     * If the app isn't already exempt from battery optimization, open the
     * system dialog that lets the user whitelist us. This is what keeps the
     * periodic sweep + foreground service alive on aggressive OEMs.
     * The dialog appears at most once per install — Android remembers the
     * user's answer.
     */
    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private fun loadFragment(fragment: Fragment, tag: String, addToBackStack: Boolean) {
        val existing = supportFragmentManager.findFragmentByTag(tag)
        if (existing != null && existing.isVisible) return
        val tx = supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment, tag)
        if (addToBackStack) tx.addToBackStack(tag)
        tx.commit()
    }

    companion object {
        const val TAG_RECORDINGS = "recordings"
        const val TAG_DASHBOARD  = "dashboard"
        const val TAG_SETTINGS   = "settings"
    }
}
