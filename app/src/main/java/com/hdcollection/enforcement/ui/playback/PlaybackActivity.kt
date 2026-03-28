package com.hdcollection.enforcement.ui.playback

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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

        val isVideo = dirName == "recordings"

        // 更新文件数量
        requireActivity().findViewById<TextView>(R.id.tvFileCount)?.text =
            "${files.size} 个文件"

        Timber.d("PlaybackActivity: found ${files.size} files in $dirName")

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerView)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = MediaFileAdapter(files, isVideo) { file ->
            if (isVideo) {
                playVideo(file)
            } else {
                viewImage(file)
            }
        }
    }

    private fun playVideo(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Timber.w(e, "No external player, using built-in viewer")
            startActivity(Intent(requireContext(), VideoPlayerActivity::class.java).apply {
                putExtra("path", file.absolutePath)
            })
        }
    }

    private fun viewImage(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Timber.w(e, "No external viewer, using built-in viewer")
            startActivity(Intent(requireContext(), ImageViewerActivity::class.java).apply {
                putExtra("path", file.absolutePath)
            })
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
    private val isVideo: Boolean,
    private val onClick: (File) -> Unit
) : RecyclerView.Adapter<MediaFileAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val ivPlayIcon: ImageView = view.findViewById(R.id.ivPlayIcon)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvFileInfo: TextView = view.findViewById(R.id.tvFileInfo)
        val tvUploadStatus: TextView = view.findViewById(R.id.tvUploadStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_media_file, parent, false))

    override fun getItemCount() = files.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]

        // 文件名 — 更友好的显示
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(file.lastModified()))
        holder.tvFileName.text = dateStr

        // 文件大小
        val sizeMb = file.length() / (1024.0 * 1024.0)
        holder.tvFileInfo.text = if (sizeMb >= 1.0) {
            String.format("%.1f MB", sizeMb)
        } else {
            String.format("%.0f KB", file.length() / 1024.0)
        }

        holder.tvUploadStatus.text = "待上传"
        holder.itemView.setOnClickListener { onClick(file) }

        // 缩略图
        if (isVideo) {
            holder.ivPlayIcon.visibility = View.VISIBLE
            loadVideoThumbnail(holder.ivThumbnail, holder.tvDuration, file)
        } else {
            holder.ivPlayIcon.visibility = View.GONE
            holder.tvDuration.visibility = View.GONE
            loadImageThumbnail(holder.ivThumbnail, file)
        }
    }

    private fun loadVideoThumbnail(imageView: ImageView, tvDuration: TextView, file: File) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)

            // 获取视频帧作为缩略图
            val bitmap = retriever.getFrameAtTime(1_000_000) // 1秒处
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            }

            // 获取时长
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (durationMs != null) {
                val totalSec = durationMs / 1000
                val min = totalSec / 60
                val sec = totalSec % 60
                tvDuration.text = String.format("%02d:%02d", min, sec)
                tvDuration.visibility = View.VISIBLE
            }

            retriever.release()
        } catch (e: Exception) {
            Timber.w(e, "Failed to load video thumbnail: ${file.name}")
        }
    }

    private fun loadImageThumbnail(imageView: ImageView, file: File) {
        try {
            // 先读取尺寸
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)

            // 计算采样率（目标宽 192px）
            val targetWidth = 192
            var sampleSize = 1
            if (options.outWidth > targetWidth) {
                sampleSize = options.outWidth / targetWidth
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load image thumbnail: ${file.name}")
        }
    }
}
