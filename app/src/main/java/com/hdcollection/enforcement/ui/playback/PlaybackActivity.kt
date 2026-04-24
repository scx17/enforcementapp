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
import android.widget.CheckBox
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            tab.text = when (position) {
                0 -> "视频回放"
                1 -> "图片回放"
                else -> "音频回放"
            }
        }.attach()

        // 全部上传按钮
        findViewById<android.widget.Button>(R.id.btnUploadAll).setOnClickListener {
            uploadAllFiles()
        }

        // 加载已上传文件状态
        loadUploadedStates()

        // 管理模式
        findViewById<android.widget.Button>(R.id.btnManage).setOnClickListener {
            enterManageMode()
        }
        findViewById<android.widget.Button>(R.id.btnCancelManage).setOnClickListener {
            exitManageMode()
        }
        findViewById<android.widget.Button>(R.id.btnDeleteSelected).setOnClickListener {
            confirmDeleteSelected()
        }
        findViewById<android.widget.Button>(R.id.btnSelectAll).setOnClickListener {
            selectAllUploaded()
        }
    }

    // 当前活跃的 fragment adapter 引用，由 fragment 设置
    internal var currentAdapter: MediaFileAdapter? = null
    internal var currentFiles: List<File>? = null

    private fun uploadAllFiles() {
        val adapter = currentAdapter
        val files = currentFiles
        if (adapter == null || files == null || files.isEmpty()) {
            android.widget.Toast.makeText(this, "没有可上传的文件", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        uploadAllFromAdapter(files, adapter)
    }

    // 全局上传状态跟踪：文件路径 → 状态（uploaded / uploading / failed）
    val uploadStates = mutableMapOf<String, String>()

    // 管理模式状态
    var isManageMode = false
    val selectedFiles = mutableSetOf<String>()

    private val db by lazy {
        com.hdcollection.enforcement.data.db.AppDatabase.getInstance(this)
    }

    /** 从 DB 加载已上传文件状态 */
    private fun loadUploadedStates() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val uploadedPaths = db.uploadQueueDao().getUploadedFilePaths()
                uploadedPaths.forEach { path -> uploadStates[path] = "uploaded" }
                Timber.i("加载已上传文件状态: ${uploadedPaths.size} 个文件")
                runOnUiThread {
                    currentAdapter?.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                Timber.e(e, "加载已上传状态失败")
            }
        }
    }

    private val uploadClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
            .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
            .build()
    }

    private val uploadService by lazy {
        val settings = com.hdcollection.enforcement.data.AppSettings(
            getSharedPreferences("app_settings", MODE_PRIVATE))
        com.hdcollection.enforcement.upload.UploadService(
            com.hdcollection.enforcement.data.db.AppDatabase.getInstance(this).uploadQueueDao(),
            settings, uploadClient, applicationContext)
    }

    /** 单文件上传，由 Adapter 调用，进度直接反映在列表项上 */
    internal fun uploadSingleFile(file: File, adapter: MediaFileAdapter, position: Int) {
        val settings = com.hdcollection.enforcement.data.AppSettings(
            getSharedPreferences("app_settings", MODE_PRIVATE))
        if (settings.deviceId.isEmpty() || settings.platformApiUrl.isEmpty()) {
            android.widget.Toast.makeText(this, "请先配置平台地址和设备ID", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (uploadStates[file.absolutePath] == "uploaded" || uploadStates[file.absolutePath] == "uploading") return

        uploadStates[file.absolutePath] = "uploading"
        adapter.notifyItemChanged(position)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val type = fileUploadType(file)
                uploadService.enqueue(settings.deviceId, type, file, null, null, file.lastModified())
                uploadService.processPendingUploads()
                uploadStates[file.absolutePath] = "uploaded"
            } catch (e: Exception) {
                Timber.e(e, "上传失败: ${file.name}")
                uploadStates[file.absolutePath] = "failed"
            }
            runOnUiThread { adapter.notifyItemChanged(position) }
        }
    }

    /** 按扩展名判定上传类型：mp4/3gp → video，m4a/aac/amr → audio，其余按 image */
    private fun fileUploadType(file: File): String = when (file.extension.lowercase()) {
        "mp4", "3gp" -> "video"
        "m4a", "aac", "amr", "mp3" -> "audio"
        else -> "image"
    }

    /** 全部上传 */
    internal fun uploadAllFromAdapter(files: List<File>, adapter: MediaFileAdapter) {
        val settings = com.hdcollection.enforcement.data.AppSettings(
            getSharedPreferences("app_settings", MODE_PRIVATE))
        if (settings.deviceId.isEmpty() || settings.platformApiUrl.isEmpty()) {
            android.widget.Toast.makeText(this, "请先配置平台地址和设备ID", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val pending = files.filterIndexed { i, f ->
            val state = uploadStates[f.absolutePath]
            state != "uploaded" && state != "uploading"
        }
        if (pending.isEmpty()) {
            android.widget.Toast.makeText(this, "没有待上传文件", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        pending.forEach { f -> uploadStates[f.absolutePath] = "uploading" }
        adapter.notifyDataSetChanged()

        CoroutineScope(Dispatchers.IO).launch {
            pending.forEachIndexed { idx, file ->
                try {
                    val type = fileUploadType(file)
                    uploadService.enqueue(settings.deviceId, type, file, null, null, file.lastModified())
                    uploadService.processPendingUploads()
                    uploadStates[file.absolutePath] = "uploaded"
                } catch (e: Exception) {
                    Timber.e(e, "上传失败: ${file.name}")
                    uploadStates[file.absolutePath] = "failed"
                }
                val pos = files.indexOf(file)
                if (pos >= 0) runOnUiThread { adapter.notifyItemChanged(pos) }
            }
            runOnUiThread {
                val ok = pending.count { uploadStates[it.absolutePath] == "uploaded" }
                val fail = pending.size - ok
                val msg = if (fail == 0) "全部上传完成 ($ok)" else "上传完成: $ok 成功, $fail 失败"
                android.widget.Toast.makeText(this@PlaybackActivity, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun enterManageMode() {
        isManageMode = true
        selectedFiles.clear()
        findViewById<View>(R.id.normalBar).visibility = View.GONE
        findViewById<View>(R.id.manageBar).visibility = View.VISIBLE
        updateDeleteButtonText()
        currentAdapter?.notifyDataSetChanged()
    }

    private fun exitManageMode() {
        isManageMode = false
        selectedFiles.clear()
        findViewById<View>(R.id.normalBar).visibility = View.VISIBLE
        findViewById<View>(R.id.manageBar).visibility = View.GONE
        currentAdapter?.notifyDataSetChanged()
    }

    internal fun updateDeleteButtonText() {
        val btn = findViewById<android.widget.Button>(R.id.btnDeleteSelected)
        val count = selectedFiles.size
        btn.text = "删除选中 ($count)"
        btn.isEnabled = count > 0
    }

    private fun selectAllUploaded() {
        val files = currentFiles ?: return
        if (files.isEmpty()) return
        // 再次点击相当于取消全选
        if (selectedFiles.size == files.size) {
            selectedFiles.clear()
        } else {
            selectedFiles.clear()
            files.forEach { selectedFiles.add(it.absolutePath) }
        }
        updateDeleteButtonText()
        currentAdapter?.notifyDataSetChanged()
    }

    /** 单个删除确认 */
    internal fun confirmDeleteSingle(file: java.io.File) {
        android.app.AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("该文件已上传至服务器，删除本地文件不影响服务器副本。\n\n确定删除 ${file.name}？")
            .setPositiveButton("删除") { _, _ -> deleteSingleFile(file) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSingleFile(file: java.io.File) {
        val path = file.absolutePath
        CoroutineScope(Dispatchers.IO).launch {
            try {
                file.delete()
                db.uploadQueueDao().deleteByFilePath(path)
                uploadStates.remove(path)
                Timber.i("已删除本地文件: ${file.name}")
                runOnUiThread {
                    refreshCurrentFragment()
                    android.widget.Toast.makeText(this@PlaybackActivity, "已删除", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Timber.e(e, "删除文件失败: ${file.name}")
                runOnUiThread {
                    android.widget.Toast.makeText(this@PlaybackActivity, "删除失败", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmDeleteSelected() {
        val count = selectedFiles.size
        if (count == 0) return
        val notUploaded = selectedFiles.count { uploadStates[it] != "uploaded" }
        val uploaded = count - notUploaded
        val msg = buildString {
            append("确定删除 $count 个文件？\n")
            if (uploaded > 0) append("· $uploaded 个已上传，本地删除不影响服务器副本。\n")
            if (notUploaded > 0) append("⚠ $notUploaded 个尚未上传，删除后无法恢复。\n")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("批量删除")
            .setMessage(msg.trim())
            .setPositiveButton("删除") { _, _ -> deleteSelectedFiles() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelectedFiles() {
        val paths = selectedFiles.toList()
        CoroutineScope(Dispatchers.IO).launch {
            var deleted = 0
            var failed = 0
            paths.forEach { path ->
                try {
                    val file = java.io.File(path)
                    if (file.exists()) file.delete()
                    deleted++
                } catch (e: Exception) {
                    Timber.e(e, "删除文件失败: $path")
                    failed++
                }
            }
            try {
                db.uploadQueueDao().deleteByFilePaths(paths)
            } catch (e: Exception) {
                Timber.e(e, "清除DB记录失败")
            }
            paths.forEach { uploadStates.remove(it) }
            Timber.i("批量删除完成: $deleted 成功, $failed 失败")
            runOnUiThread {
                exitManageMode()
                refreshCurrentFragment()
                val msg = if (failed == 0) "已删除 $deleted 个文件" else "删除 $deleted 成功, $failed 失败"
                android.widget.Toast.makeText(this@PlaybackActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshCurrentFragment() {
        val viewPager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
        val currentItem = viewPager.currentItem
        viewPager.adapter = PlaybackPagerAdapter(this)
        viewPager.setCurrentItem(currentItem, false)
        val tabLayout = findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "视频回放"
                1 -> "图片回放"
                else -> "音频回放"
            }
        }.attach()
    }

    inner class PlaybackPagerAdapter(activity: AppCompatActivity) :
        FragmentStateAdapter(activity) {
        override fun getItemCount() = 3
        override fun createFragment(position: Int) = MediaListFragment.newInstance(
            when (position) {
                0 -> "recordings"
                1 -> "photos"
                else -> "audios"
            }
        )
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
        val dir = File(requireActivity().filesDir, dirName)
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        val mediaType = when (dirName) {
            "recordings" -> "video"
            "audios" -> "audio"
            else -> "image"
        }

        // 更新文件数量
        requireActivity().findViewById<TextView>(R.id.tvFileCount)?.text =
            "${files.size} 个文件"

        Timber.d("PlaybackActivity: found ${files.size} files in $dirName (type=$mediaType)")

        val activity = requireActivity() as PlaybackActivity
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerView)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        val adapter = MediaFileAdapter(files, mediaType, activity.uploadStates, activity, onClick = { file ->
            when (mediaType) {
                "video" -> playVideo(file)
                "audio" -> playAudio(file)
                else -> viewImage(file)
            }
        }, onUpload = { file, adapterRef, position ->
            activity.uploadSingleFile(file, adapterRef, position)
        })
        recycler.adapter = adapter
        activity.currentAdapter = adapter
        activity.currentFiles = files
    }

    private fun playAudio(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Timber.w(e, "No external audio player for ${file.name}")
            android.widget.Toast.makeText(requireContext(), "未找到可用的音频播放器", android.widget.Toast.LENGTH_SHORT).show()
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
    private val mediaType: String, // "video" / "image" / "audio"
    private val uploadStates: Map<String, String>,
    private val activity: PlaybackActivity,
    private val onClick: (File) -> Unit,
    private val onUpload: (File, MediaFileAdapter, Int) -> Unit
) : RecyclerView.Adapter<MediaFileAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val ivPlayIcon: ImageView = view.findViewById(R.id.ivPlayIcon)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvFileInfo: TextView = view.findViewById(R.id.tvFileInfo)
        val btnUpload: android.widget.Button = view.findViewById(R.id.btnUpload)
        val progressUpload: android.widget.ProgressBar = view.findViewById(R.id.progressUpload)
        val layoutUploaded: View = view.findViewById(R.id.layoutUploaded)
        val tvUploaded: TextView = view.findViewById(R.id.tvUploaded)
        val btnDelete: ImageView = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_media_file, parent, false))

    override fun getItemCount() = files.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        val state = uploadStates[file.absolutePath]
        val isUploaded = state == "uploaded"

        // CheckBox — 管理模式下所有文件均可勾选，删除时再对未上传文件发警告
        if (activity.isManageMode) {
            holder.cbSelect.visibility = View.VISIBLE
            holder.cbSelect.setOnCheckedChangeListener(null)
            holder.cbSelect.isChecked = activity.selectedFiles.contains(file.absolutePath)
            holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    activity.selectedFiles.add(file.absolutePath)
                } else {
                    activity.selectedFiles.remove(file.absolutePath)
                }
                activity.updateDeleteButtonText()
            }
            holder.itemView.setOnClickListener {
                holder.cbSelect.isChecked = !holder.cbSelect.isChecked
            }
        } else {
            holder.cbSelect.visibility = View.GONE
            holder.cbSelect.setOnCheckedChangeListener(null)
            holder.itemView.setOnClickListener { onClick(file) }
        }

        // 文件名
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

        // 上传状态切换显示
        when (state) {
            "uploading" -> {
                holder.btnUpload.visibility = View.GONE
                holder.progressUpload.visibility = View.VISIBLE
                holder.progressUpload.isIndeterminate = true
                holder.layoutUploaded.visibility = View.GONE
            }
            "uploaded" -> {
                holder.btnUpload.visibility = View.GONE
                holder.progressUpload.visibility = View.GONE
                holder.layoutUploaded.visibility = View.VISIBLE
                holder.tvUploaded.text = "已上传"
                holder.tvUploaded.setTextColor(0xFF67C23A.toInt())
                holder.btnDelete.visibility = if (activity.isManageMode) View.GONE else View.VISIBLE
                holder.btnDelete.setOnClickListener {
                    activity.confirmDeleteSingle(file)
                }
            }
            "failed" -> {
                holder.btnUpload.visibility = View.VISIBLE
                holder.btnUpload.text = "重试"
                holder.btnUpload.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF56C6C.toInt())
                holder.progressUpload.visibility = View.GONE
                holder.layoutUploaded.visibility = View.GONE
            }
            else -> {
                holder.btnUpload.visibility = View.VISIBLE
                holder.btnUpload.text = "上传"
                holder.btnUpload.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF409EFF.toInt())
                holder.progressUpload.visibility = View.GONE
                holder.layoutUploaded.visibility = View.GONE
            }
        }

        holder.btnUpload.setOnClickListener { onUpload(file, this, position) }

        // 缩略图（ViewHolder 复用：每次显式重置 ivPlayIcon 图标避免错乱）
        when (mediaType) {
            "video" -> {
                holder.ivPlayIcon.visibility = View.VISIBLE
                holder.ivPlayIcon.setImageResource(android.R.drawable.ic_media_play)
                loadVideoThumbnail(holder.ivThumbnail, holder.tvDuration, file)
            }
            "audio" -> {
                holder.ivPlayIcon.visibility = View.VISIBLE
                holder.ivPlayIcon.setImageResource(android.R.drawable.ic_btn_speak_now)
                holder.ivThumbnail.setImageResource(android.R.color.darker_gray)
                loadAudioDuration(holder.tvDuration, file)
            }
            else -> {
                holder.ivPlayIcon.visibility = View.GONE
                holder.tvDuration.visibility = View.GONE
                loadImageThumbnail(holder.ivThumbnail, file)
            }
        }
    }

    private fun loadAudioDuration(tvDuration: TextView, file: File) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (durationMs != null) {
                val totalSec = durationMs / 1000
                tvDuration.text = String.format("%02d:%02d", totalSec / 60, totalSec % 60)
                tvDuration.visibility = View.VISIBLE
            } else {
                tvDuration.visibility = View.GONE
            }
            retriever.release()
        } catch (e: Exception) {
            Timber.w(e, "Failed to read audio duration: ${file.name}")
            tvDuration.visibility = View.GONE
        }
    }

    private fun loadVideoThumbnail(imageView: ImageView, tvDuration: TextView, file: File) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val bitmap = retriever.getFrameAtTime(1_000_000)
            if (bitmap != null) imageView.setImageBitmap(bitmap)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (durationMs != null) {
                val totalSec = durationMs / 1000
                tvDuration.text = String.format("%02d:%02d", totalSec / 60, totalSec % 60)
                tvDuration.visibility = View.VISIBLE
            }
            retriever.release()
        } catch (e: Exception) {
            Timber.w(e, "Failed to load video thumbnail: ${file.name}")
        }
    }

    private fun loadImageThumbnail(imageView: ImageView, file: File) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val targetWidth = 192
            var sampleSize = 1
            if (options.outWidth > targetWidth) sampleSize = options.outWidth / targetWidth
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            if (bitmap != null) imageView.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Timber.w(e, "Failed to load image thumbnail: ${file.name}")
        }
    }
}
