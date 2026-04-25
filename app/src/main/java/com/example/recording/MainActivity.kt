package com.example.recording

import android.os.Bundle
import android.widget.CompoundButton
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply night mode before inflating layout
        applyNightMode()

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawerLayout)

        // Default fragment on first create
        if (savedInstanceState == null) {
            loadFragment(RecordingsFragment(), TAG_RECORDINGS)
        }

        // Drawer items
        findViewById<android.view.View>(R.id.drawerItemRecordings).setOnClickListener {
            loadFragment(RecordingsFragment(), TAG_RECORDINGS)
            drawerLayout.closeDrawers()
        }

        findViewById<android.view.View>(R.id.drawerItemDashboard).setOnClickListener {
            loadFragment(DashboardFragment(), TAG_DASHBOARD)
            drawerLayout.closeDrawers()
        }

        findViewById<android.view.View>(R.id.drawerItemSettings).setOnClickListener {
            loadFragment(SettingsFragment(), TAG_SETTINGS)
            drawerLayout.closeDrawers()
        }

        // Night mode toggle in drawer footer
        val switchNightMode = findViewById<Switch>(R.id.switchNightMode)
        switchNightMode.isChecked = AppPrefs.isNightMode(this)
        switchNightMode.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            AppPrefs.setNightMode(this, checked)
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_YES
                else         AppCompatDelegate.MODE_NIGHT_NO
            )
            recreate()
        }
    }

    /** Opens the navigation drawer from any fragment. */
    fun openDrawer() = drawerLayout.openDrawer(
        androidx.core.view.GravityCompat.START
    )

    private fun loadFragment(fragment: Fragment, tag: String) {
        val existing = supportFragmentManager.findFragmentByTag(tag)
        if (existing != null && existing.isVisible) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment, tag)
            .commit()
    }

    private fun applyNightMode() {
        val nightMode = if (AppPrefs.isNightMode(this)) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    companion object {
        const val TAG_RECORDINGS = "recordings"
        const val TAG_DASHBOARD  = "dashboard"
        const val TAG_SETTINGS   = "settings"
    }
}
