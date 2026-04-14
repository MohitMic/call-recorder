package com.example.recording

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        // Load default fragment on first create
        if (savedInstanceState == null) {
            loadFragment(DashboardFragment(), TAG_DASHBOARD)
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> { loadFragment(DashboardFragment(), TAG_DASHBOARD); true }
                R.id.nav_recordings -> { loadFragment(RecordingsFragment(), TAG_RECORDINGS); true }
                R.id.nav_settings -> { loadFragment(SettingsFragment(), TAG_SETTINGS); true }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment, tag: String) {
        val existing = supportFragmentManager.findFragmentByTag(tag)
        if (existing != null && existing.isVisible) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment, tag)
            .commit()
    }

    companion object {
        const val TAG_DASHBOARD = "dashboard"
        const val TAG_RECORDINGS = "recordings"
        const val TAG_SETTINGS = "settings"
    }
}
