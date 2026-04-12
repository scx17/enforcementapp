package com.hdcollection.enforcement.ui.upload

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.data.AppSettings
import com.hdcollection.enforcement.data.db.AppDatabase
import com.hdcollection.enforcement.upload.UploadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class LocalFile(
    val file: File,
    val type: String, // "video" | "image"
    var selected: Boolean = false
)

class ManualUploadFragment : Fragment() {

    private lateinit var settings: AppSettings
    private val allFiles = mutableListOf<LocalFile>()
    private val displayFiles = mutableListOf<LocalFile>()
    private var adapter: FileAdapter? = null
    private var currentFilter = "all"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_manual_upload, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = AppSettings(requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE))

        val recycler = view.findViewById<RecyclerView>(R.id.fileList)
        val btnUpload = view.findViewById<Button>(R.id.btnUpload)
        val btnSelectAll = view.findViewById<Button>(R.id.btnSelectAll)
        val tvInfo = view.findViewById<TextView>(R.id.tvSelectedInfo)
        val filterGroup = view.findViewById<RadioGroup>(R.id.filterGroup)

        adapter = FileAdapter(displayFiles) { updateSelection(tvInfo, btnUpload) }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        filterGroup.setOnCheckedChangeListener { _, checkedId ->
            currentFilter = when (checkedId) {
                R.id.filterVideo -> "video"
                R.id.filterImage -> "image"
                else -> "all"
            }
            applyFilter()
        }

        btnSelectAll.setOnClickListener {
            val allSelected = displayFiles.all { it.selected }
            displayFiles.forEach { it.selected = !allSelected }
            adapter?.notifyDataSetChanged()
            updateSelection(tvInfo, btnUpload)
        }

        btnUpload.setOnClickListener { uploadSelected() }

        scanFiles()
    }

    private fun scanFiles() {
        allFiles.clear()
        val recDir = File(requireContext().filesDir, "recordings")
        val photoDir = File(requireContext().filesDir, "photos")

        recDir?.listFiles()?.filter { it.extension in listOf("mp4", "3gp") }?.forEach {
            allFiles.add(LocalFile(it, "video"))
        }
        photoDir?.listFiles()?.filter { it.extension in listOf("jpg", "jpeg", "png") }?.forEach {
            allFiles.add(LocalFile(it, "image"))
        }
        allFiles.sortByDescending { it.file.lastModified() }
        applyFilter()
    }

    private fun applyFilter() {
        displayFiles.clear()
        displayFiles.addAll(
            if (currentFilter == "all") allFiles
            else allFiles.filter { it.type == currentFilter }
        )
        adapter?.notifyDataSetChanged()
    }

    private fun updateSelection(tvInfo: TextView, btnUpload: Button) {
        val selected = displayFiles.filter { it.selected }
        val totalSize = selected.sumOf { it.file.length() }
        val sizeStr = if (totalSize < 1024 * 1024) "${totalSize / 1024}KB" else "${totalSize / 1024 / 1024}MB"
        tvInfo.text = "已选 ${selected.size} 个文件 ($sizeStr)"
        btnUpload.isEnabled = selected.isNotEmpty()
    }

    private fun uploadSelected() {
        val selected = displayFiles.filter { it.selected }
        if (selected.isEmpty()) return

        lifecycleScope.launch {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.MINUTES)
                .readTimeout(5, TimeUnit.MINUTES)
                .build()
            val service = UploadService(
                AppDatabase.getInstance(requireContext()).uploadQueueDao(),
                settings, client, requireContext().applicationContext
            )

            withContext(Dispatchers.IO) {
                selected.forEach { item ->
                    service.enqueue(
                        deviceId = settings.deviceId,
                        fileType = item.type,
                        file = item.file,
                        lat = null, lng = null,
                        recordTime = item.file.lastModified()
                    )
                }
                service.processPendingUploads()
            }

            Timber.i("手动上传: ${selected.size} 个文件已加入队列")
            Toast.makeText(requireContext(), "已加入上传队列 (${selected.size}个)", Toast.LENGTH_SHORT).show()
            // 取消选中状态
            displayFiles.forEach { it.selected = false }
            adapter?.notifyDataSetChanged()
        }
    }

    // ========== RecyclerView Adapter ==========
    class FileAdapter(
        private val items: List<LocalFile>,
        private val onSelectionChange: () -> Unit
    ) : RecyclerView.Adapter<FileAdapter.VH>() {

        private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val cbSelect: CheckBox = view.findViewById(android.R.id.checkbox)
            val tvName: TextView = view.findViewById(android.R.id.text1)
            val tvInfo: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(
                android.R.layout.simple_list_item_multiple_choice, parent, false
            )
            // 重新布局为深色风格
            val layout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(32, 16, 32, 16)
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val cb = CheckBox(parent.context).apply {
                id = android.R.id.checkbox
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val textContainer = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 16
                }
            }
            val tv1 = TextView(parent.context).apply {
                id = android.R.id.text1
                textSize = 14f
                setTextColor(0xFFEEEEEE.toInt())
                maxLines = 1
            }
            val tv2 = TextView(parent.context).apply {
                id = android.R.id.text2
                textSize = 12f
                setTextColor(0xFF999999.toInt())
            }
            textContainer.addView(tv1)
            textContainer.addView(tv2)
            layout.addView(cb)
            layout.addView(textContainer)
            return VH(layout)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.file.name
            val size = if (item.file.length() < 1024 * 1024)
                "${item.file.length() / 1024}KB" else "${item.file.length() / 1024 / 1024}MB"
            val typeLabel = if (item.type == "video") "视频" else "图片"
            holder.tvInfo.text = "$typeLabel | $size | ${dateFmt.format(Date(item.file.lastModified()))}"
            holder.cbSelect.isChecked = item.selected
            holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                item.selected = isChecked
                onSelectionChange()
            }
            holder.itemView.setOnClickListener {
                item.selected = !item.selected
                holder.cbSelect.isChecked = item.selected
                onSelectionChange()
            }
        }

        override fun getItemCount() = items.size
    }
}
