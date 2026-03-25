package com.hdcollection.enforcement.ui.playback

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.hdcollection.enforcement.R
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlaybackActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        val viewPager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = PlaybackPagerAdapter(this)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) "视频回放" else "图片回放"
        }.attach()
    }

    inner class PlaybackPagerAdapter(activity: AppCompatActivity) :
        FragmentStateAdapter(activity) {
        override fun getItemCount() = 2
        override fun createFragment(position: Int) =
            MediaListFragment.newInstance(if (position == 0) "recordings" else "photos")
    }
}

class MediaListFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_media_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val dirName = arguments?.getString(ARG_DIR) ?: "recordings"
        val dir = requireActivity().getExternalFilesDir(dirName) ?: requireActivity().filesDir
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        Timber.d("PlaybackActivity: found ${files.size} files in $dirName")

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerView)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = MediaFileAdapter(files) { file ->
            openFile(file, dirName)
        }
    }

    private fun openFile(file: File, dirName: String) {
        val uri = Uri.fromFile(file)
        val intent = if (dirName == "recordings") {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "No app to open file: ${file.name}")
        }
    }

    companion object {
        private const val ARG_DIR = "dir"
        fun newInstance(dir: String) = MediaListFragment().apply {
            arguments = Bundle().apply { putString(ARG_DIR, dir) }
        }
    }
}

class MediaFileAdapter(
    private val files: List<File>,
    private val onClick: (File) -> Unit
) : RecyclerView.Adapter<MediaFileAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvFileInfo: TextView = view.findViewById(R.id.tvFileInfo)
        val tvUploadStatus: TextView = view.findViewById(R.id.tvUploadStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_media_file, parent, false))

    override fun getItemCount() = files.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.tvFileName.text = file.name
        val sizeMb = file.length() / (1024.0 * 1024.0)
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
        holder.tvFileInfo.text = String.format("%.1fMB  %s", sizeMb, dateStr)
        holder.tvUploadStatus.text = "待上传"
        holder.itemView.setOnClickListener { onClick(file) }
    }
}
