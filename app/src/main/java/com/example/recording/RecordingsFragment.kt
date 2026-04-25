package com.example.recording

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingsFragment : Fragment(R.layout.fragment_recordings) {

    private lateinit var recycler: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var progressLoad: ProgressBar
    private lateinit var adapter: RecordingCardAdapter

    // Top bar
    private lateinit var topBar: View
    private lateinit var layoutSearchBar: LinearLayout
    private lateinit var editSearch: EditText

    // Filter tabs
    private lateinit var tabAll: TextView
    private lateinit var tabIncoming: TextView
    private lateinit var tabOutgoing: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recycler      = view.findViewById(R.id.recyclerRecordings)
        layoutEmpty   = view.findViewById(R.id.layoutEmpty)
        progressLoad  = view.findViewById(R.id.progressLoad)
        topBar        = view.findViewById(R.id.topBar)
        layoutSearchBar = view.findViewById(R.id.layoutSearchBar)
        editSearch    = view.findViewById(R.id.editSearch)
        tabAll        = view.findViewById(R.id.tabAll)
        tabIncoming   = view.findViewById(R.id.tabIncoming)
        tabOutgoing   = view.findViewById(R.id.tabOutgoing)

        adapter = RecordingCardAdapter { item ->
            val intent = Intent(requireContext(), RecordingsPlaybackActivity::class.java)
            intent.putExtra(RecordingsPlaybackActivity.EXTRA_FILE_PATH, item.file.absolutePath)
            startActivity(intent)
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        // Hamburger → open the nav drawer
        view.findViewById<View>(R.id.btnMenu).setOnClickListener {
            (activity as? MainActivity)?.openDrawer()
        }

        // Search icon toggle
        view.findViewById<View>(R.id.btnSearch).setOnClickListener {
            topBar.visibility = View.GONE
            layoutSearchBar.visibility = View.VISIBLE
            editSearch.requestFocus()
            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            imm.showSoftInput(editSearch, InputMethodManager.SHOW_IMPLICIT)
        }

        // Cancel search
        view.findViewById<View>(R.id.btnCancelSearch).setOnClickListener {
            hideSearch()
        }

        // Search input live filter
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                adapter.setSearchQuery(s?.toString() ?: "")
            }
        })

        // Filter tabs
        tabAll.setOnClickListener      { selectTab("ALL") }
        tabIncoming.setOnClickListener { selectTab("INCOMING") }
        tabOutgoing.setOnClickListener { selectTab("OUTGOING") }
        selectTab("ALL")

        loadRecordings()
    }

    override fun onResume() {
        super.onResume()
        loadRecordings()
    }

    // ── Tab selection ─────────────────────────────────────────────────────────

    private fun selectTab(dir: String) {
        val ctx = requireContext()
        val activeColor   = ContextCompat.getColor(ctx, R.color.pink)
        val inactiveColor = ContextCompat.getColor(ctx, R.color.text_secondary_dark)

        tabAll.setTextColor(     if (dir == "ALL")      activeColor else inactiveColor)
        tabIncoming.setTextColor(if (dir == "INCOMING") activeColor else inactiveColor)
        tabOutgoing.setTextColor(if (dir == "OUTGOING") activeColor else inactiveColor)

        // Bold the active tab
        tabAll.setTypeface(null,      if (dir == "ALL")      android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        tabIncoming.setTypeface(null, if (dir == "INCOMING") android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        tabOutgoing.setTypeface(null, if (dir == "OUTGOING") android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

        adapter.setDirectionFilter(dir)
    }

    // ── Search bar ────────────────────────────────────────────────────────────

    private fun hideSearch() {
        topBar.visibility = View.VISIBLE
        layoutSearchBar.visibility = View.GONE
        editSearch.setText("")
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(editSearch.windowToken, 0)
        adapter.setSearchQuery("")
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadRecordings() {
        progressLoad.visibility = View.VISIBLE
        layoutEmpty.visibility  = View.GONE
        recycler.visibility     = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                RecordingCardAdapter.loadItems(requireContext())
            }
            progressLoad.visibility = View.GONE
            if (items.isEmpty()) {
                layoutEmpty.visibility = View.VISIBLE
            } else {
                recycler.visibility = View.VISIBLE
                adapter.submitFull(items)
            }
        }
    }
}
