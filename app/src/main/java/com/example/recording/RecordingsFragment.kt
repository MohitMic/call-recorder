package com.example.recording

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recycler     = view.findViewById(R.id.recyclerRecordings)
        layoutEmpty  = view.findViewById(R.id.layoutEmpty)
        progressLoad = view.findViewById(R.id.progressLoad)

        adapter = RecordingCardAdapter(
            onPlayClick = { item ->
                val intent = Intent(requireContext(), RecordingsPlaybackActivity::class.java)
                intent.putExtra(RecordingsPlaybackActivity.EXTRA_FILE_PATH, item.file.absolutePath)
                startActivity(intent)
            },
            onDebugClick = { item ->
                val intent = Intent(requireContext(), RecordingsDebugActivity::class.java)
                intent.putExtra(RecordingsDebugActivity.EXTRA_FILE_PATH, item.file.absolutePath)
                startActivity(intent)
            }
        )

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        loadRecordings()
    }

    override fun onResume() {
        super.onResume()
        loadRecordings()
    }

    private fun loadRecordings() {
        progressLoad.visibility = View.VISIBLE
        layoutEmpty.visibility  = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                RecordingCardAdapter.loadItems(requireContext())
            }
            progressLoad.visibility = View.GONE
            if (items.isEmpty()) {
                layoutEmpty.visibility = View.VISIBLE
                recycler.visibility    = View.GONE
            } else {
                layoutEmpty.visibility = View.GONE
                recycler.visibility    = View.VISIBLE
                adapter.submitList(items)
            }
        }
    }
}
