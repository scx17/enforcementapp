# 删除录制文件和照片功能 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在执法仪 App 的文件回放页面（PlaybackActivity）增加删除功能，支持单个删除和批量删除，且只允许删除已上传至服务器的文件。

**Architecture:** 修改 UploadService 上传成功后保留 "done" 记录（而非删除），PlaybackActivity 启动时从 DB 加载已上传状态。单个删除在列表项状态区加删除按钮；批量删除通过底部栏"管理"按钮进入选择模式，CheckBox 仅出现在已上传文件上。删除只删本地文件 + DB 记录，服务器副本不受影响。

**Tech Stack:** Kotlin, Room, RecyclerView, AlertDialog, Android Vector Drawable

---

### Task 1: UploadService 保留上传完成记录

**Files:**
- Modify: `app/src/main/java/com/hdcollection/enforcement/upload/UploadService.kt:66`
- Modify: `app/src/main/java/com/hdcollection/enforcement/data/db/UploadQueueDao.kt`

**Step 1: UploadService — 上传成功后改为 updateStatus 而非 delete**

在 `UploadService.kt` 第 66 行，将：
```kotlin
dao.delete(item.id)
```
改为：
```kotlin
dao.updateStatus(item.id, "done")
```

**Step 2: UploadQueueDao — 新增查询已上传文件路径的方法**

在 `UploadQueueDao.kt` 末尾 `}` 前新增：
```kotlin
@Query("SELECT filePath FROM upload_queue WHERE status = 'done'")
suspend fun getUploadedFilePaths(): List<String>

@Query("DELETE FROM upload_queue WHERE filePath = :filePath AND status = 'done'")
suspend fun deleteByFilePath(filePath: String)

@Query("DELETE FROM upload_queue WHERE filePath IN (:filePaths) AND status = 'done'")
suspend fun deleteByFilePaths(filePaths: List<String>)
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/hdcollection/enforcement/upload/UploadService.kt \
       app/src/main/java/com/hdcollection/enforcement/data/db/UploadQueueDao.kt
git commit -m "feat: 上传成功后保留done记录，为删除功能提供已上传状态查询"
```

---

### Task 2: 创建删除图标 drawable

**Files:**
- Create: `app/src/main/res/drawable/ic_delete.xml`

**Step 1: 创建删除图标矢量资源**

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="#F56C6C">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"/>
</vector>
```

**Step 2: Commit**

```bash
git add app/src/main/res/drawable/ic_delete.xml
git commit -m "feat: 添加删除图标矢量资源"
```

---

### Task 3: 修改列表项布局 — 支持单个删除按钮和批量选择 CheckBox

**Files:**
- Modify: `app/src/main/res/layout/item_media_file.xml`

**Step 1: 在缩略图左侧添加 CheckBox（默认隐藏），在状态区添加删除按钮**

完整替换 `item_media_file.xml` 为：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="10dp"
    android:gravity="center_vertical"
    android:background="?attr/selectableItemBackground">

    <!-- 批量选择 CheckBox（默认隐藏，管理模式下显示） -->
    <CheckBox
        android:id="@+id/cbSelect"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginEnd="8dp"
        android:visibility="gone"
        android:buttonTint="#409EFF" />

    <!-- 缩略图 -->
    <FrameLayout
        android:layout_width="96dp"
        android:layout_height="72dp">

        <ImageView
            android:id="@+id/ivThumbnail"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:scaleType="centerCrop"
            android:background="#222222" />

        <!-- 视频时长标识 -->
        <TextView
            android:id="@+id/tvDuration"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="bottom|end"
            android:background="#99000000"
            android:textColor="#FFFFFF"
            android:textSize="10sp"
            android:paddingStart="4dp"
            android:paddingEnd="4dp"
            android:paddingTop="1dp"
            android:paddingBottom="1dp"
            android:visibility="gone" />

        <!-- 播放图标覆盖（仅视频） -->
        <ImageView
            android:id="@+id/ivPlayIcon"
            android:layout_width="28dp"
            android:layout_height="28dp"
            android:layout_gravity="center"
            android:src="@android:drawable/ic_media_play"
            android:alpha="0.8"
            android:visibility="gone" />

    </FrameLayout>

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical"
        android:layout_marginStart="12dp">

        <TextView
            android:id="@+id/tvFileName"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textColor="#FFFFFF"
            android:textSize="14sp"
            android:maxLines="1"
            android:ellipsize="middle" />

        <TextView
            android:id="@+id/tvFileInfo"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textColor="#AAAAAA"
            android:textSize="12sp"
            android:layout_marginTop="4dp" />

    </LinearLayout>

    <!-- 上传状态区域：按钮 / 进度条 / 已上传+删除 三选一 -->
    <FrameLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center_vertical">

        <Button
            android:id="@+id/btnUpload"
            android:layout_width="64dp"
            android:layout_height="32dp"
            android:text="上传"
            android:textSize="11sp"
            android:textColor="#ffffff"
            android:backgroundTint="#409EFF"
            android:padding="0dp"
            android:insetTop="0dp"
            android:insetBottom="0dp" />

        <ProgressBar
            android:id="@+id/progressUpload"
            style="?android:attr/progressBarStyleHorizontal"
            android:layout_width="64dp"
            android:layout_height="20dp"
            android:layout_gravity="center"
            android:max="100"
            android:visibility="gone" />

        <!-- 已上传状态：文字 + 删除按钮 -->
        <LinearLayout
            android:id="@+id/layoutUploaded"
            android:layout_width="wrap_content"
            android:layout_height="32dp"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:visibility="gone">

            <TextView
                android:id="@+id/tvUploaded"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="已上传"
                android:textSize="11sp"
                android:textColor="#67C23A" />

            <ImageView
                android:id="@+id/btnDelete"
                android:layout_width="24dp"
                android:layout_height="24dp"
                android:layout_marginStart="6dp"
                android:src="@drawable/ic_delete"
                android:contentDescription="删除"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:padding="2dp" />

        </LinearLayout>

    </FrameLayout>

</LinearLayout>
```

**Step 2: Commit**

```bash
git add app/src/main/res/layout/item_media_file.xml
git commit -m "feat: 列表项布局增加CheckBox和删除按钮"
```

---

### Task 4: 修改底部栏布局 — 增加管理/删除模式

**Files:**
- Modify: `app/src/main/res/layout/activity_playback.xml`

**Step 1: 底部栏增加"管理"按钮，以及删除模式下的"删除选中"和"取消"按钮**

将 `activity_playback.xml` 中底部 `uploadBar` LinearLayout（第 62-91 行）替换为：

```xml
    <!-- 底部操作栏 -->
    <LinearLayout
        android:id="@+id/uploadBar"
        android:layout_width="match_parent"
        android:layout_height="48dp"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingHorizontal="16dp"
        android:background="#1A237E">

        <!-- 正常模式 -->
        <LinearLayout
            android:id="@+id/normalBar"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <Button
                android:id="@+id/btnUploadAll"
                android:layout_width="wrap_content"
                android:layout_height="36dp"
                android:text="全部上传"
                android:textSize="13sp"
                android:textColor="#ffffff"
                android:backgroundTint="#409EFF" />

            <Button
                android:id="@+id/btnManage"
                android:layout_width="wrap_content"
                android:layout_height="36dp"
                android:layout_marginStart="12dp"
                android:text="管理"
                android:textSize="13sp"
                android:textColor="#ffffff"
                android:backgroundTint="#E6A23C" />

            <TextView
                android:id="@+id/tvUploadTip"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="12dp"
                android:textColor="#80FFFFFF"
                android:textSize="12sp"
                android:text="点击单个文件的上传按钮或全部上传" />

        </LinearLayout>

        <!-- 管理模式（默认隐藏） -->
        <LinearLayout
            android:id="@+id/manageBar"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:visibility="gone">

            <Button
                android:id="@+id/btnDeleteSelected"
                android:layout_width="wrap_content"
                android:layout_height="36dp"
                android:text="删除选中 (0)"
                android:textSize="13sp"
                android:textColor="#ffffff"
                android:backgroundTint="#F56C6C"
                android:enabled="false" />

            <Button
                android:id="@+id/btnSelectAll"
                android:layout_width="wrap_content"
                android:layout_height="36dp"
                android:layout_marginStart="12dp"
                android:text="全选"
                android:textSize="13sp"
                android:textColor="#ffffff"
                android:backgroundTint="#409EFF" />

            <View
                android:layout_width="0dp"
                android:layout_height="1dp"
                android:layout_weight="1" />

            <Button
                android:id="@+id/btnCancelManage"
                android:layout_width="wrap_content"
                android:layout_height="36dp"
                android:text="取消"
                android:textSize="13sp"
                android:textColor="#ffffff"
                android:backgroundTint="#909399" />

        </LinearLayout>

    </LinearLayout>
```

**Step 2: Commit**

```bash
git add app/src/main/res/layout/activity_playback.xml
git commit -m "feat: 底部栏增加管理模式布局（删除选中/全选/取消）"
```

---

### Task 5: 实现 PlaybackActivity 删除逻辑

**Files:**
- Modify: `app/src/main/java/com/hdcollection/enforcement/ui/playback/PlaybackActivity.kt`

这是核心改动，包括：

**Step 1: 添加管理模式状态和 DB 初始化**

在 `PlaybackActivity` 类中（`uploadStates` 声明之后，约第 70 行后）添加：

```kotlin
// 管理模式状态
var isManageMode = false
val selectedFiles = mutableSetOf<String>() // 选中的文件路径

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
```

**Step 2: 在 onCreate 中调用 loadUploadedStates 并绑定管理模式按钮**

在 `onCreate` 方法中 `btnUploadAll` 点击事件之后添加：

```kotlin
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
```

**Step 3: 实现管理模式进入/退出方法**

在 `PlaybackActivity` 类中添加：

```kotlin
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

fun toggleFileSelection(filePath: String) {
    if (selectedFiles.contains(filePath)) {
        selectedFiles.remove(filePath)
    } else {
        selectedFiles.add(filePath)
    }
    updateDeleteButtonText()
}

private fun updateDeleteButtonText() {
    val btn = findViewById<android.widget.Button>(R.id.btnDeleteSelected)
    val count = selectedFiles.size
    btn.text = "删除选中 ($count)"
    btn.isEnabled = count > 0
}

private fun selectAllUploaded() {
    val files = currentFiles ?: return
    val uploadedFiles = files.filter { uploadStates[it.absolutePath] == "uploaded" }
    if (selectedFiles.size == uploadedFiles.size) {
        // 已全选 → 取消全选
        selectedFiles.clear()
    } else {
        selectedFiles.clear()
        uploadedFiles.forEach { selectedFiles.add(it.absolutePath) }
    }
    updateDeleteButtonText()
    currentAdapter?.notifyDataSetChanged()
}
```

**Step 4: 实现单个删除和批量删除**

```kotlin
/** 单个删除确认 */
internal fun confirmDeleteSingle(file: File) {
    android.app.AlertDialog.Builder(this)
        .setTitle("确认删除")
        .setMessage("该文件已上传至服务器，删除本地文件不影响服务器副本。\n\n确定删除 ${file.name}？")
        .setPositiveButton("删除") { _, _ -> deleteSingleFile(file) }
        .setNegativeButton("取消", null)
        .show()
}

private fun deleteSingleFile(file: File) {
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
    android.app.AlertDialog.Builder(this)
        .setTitle("批量删除")
        .setMessage("确定删除 $count 个已上传文件？\n本地删除不影响服务器副本。")
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
                val file = File(path)
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
    // 重新关联 TabLayout
    val tabLayout = findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout)
    TabLayoutMediator(tabLayout, viewPager) { tab, position ->
        tab.text = if (position == 0) "视频回放" else "图片回放"
    }.attach()
}
```

**Step 5: Commit**

```bash
git add app/src/main/java/com/hdcollection/enforcement/ui/playback/PlaybackActivity.kt
git commit -m "feat: PlaybackActivity实现单个删除和批量删除逻辑"
```

---

### Task 6: 修改 MediaFileAdapter 支持删除按钮和 CheckBox

**Files:**
- Modify: `app/src/main/java/com/hdcollection/enforcement/ui/playback/PlaybackActivity.kt` (MediaFileAdapter 类)

**Step 1: 修改 MediaFileAdapter 构造函数、ViewHolder 和 onBindViewHolder**

将 `MediaFileAdapter` 类（约第 255-390 行）完整替换为：

```kotlin
class MediaFileAdapter(
    private val files: List<File>,
    private val isVideo: Boolean,
    private val uploadStates: Map<String, String>,
    private val activity: PlaybackActivity,
    private val onClick: (File) -> Unit,
    private val onUpload: (File, MediaFileAdapter, Int) -> Unit
) : RecyclerView.Adapter<MediaFileAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbSelect: android.widget.CheckBox = view.findViewById(R.id.cbSelect)
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

        // CheckBox — 管理模式下，已上传的文件显示
        if (activity.isManageMode && isUploaded) {
            holder.cbSelect.visibility = View.VISIBLE
            holder.cbSelect.isChecked = activity.selectedFiles.contains(file.absolutePath)
            holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    activity.selectedFiles.add(file.absolutePath)
                } else {
                    activity.selectedFiles.remove(file.absolutePath)
                }
                activity.toggleFileSelection(file.absolutePath)
                // toggleFileSelection 会再次 toggle，这里直接更新按钮文字
            }
            // 管理模式下点击整行也切换选中
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
                // 管理模式下隐藏单个删除按钮
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

    // loadVideoThumbnail 和 loadImageThumbnail 保持不变
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
```

**Step 2: 更新 MediaListFragment 中创建 Adapter 的调用**

将 `MediaListFragment.onViewCreated` 中创建 adapter 的代码（约第 193-201 行）替换为：

```kotlin
val adapter = MediaFileAdapter(files, isVideo, activity.uploadStates, activity, onClick = { file ->
    if (isVideo) {
        playVideo(file)
    } else {
        viewImage(file)
    }
}, onUpload = { file, adapterRef, position ->
    activity.uploadSingleFile(file, adapterRef, position)
})
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/hdcollection/enforcement/ui/playback/PlaybackActivity.kt
git commit -m "feat: MediaFileAdapter支持删除按钮和批量选择CheckBox"
```

---

### Task 7: 验证构建

**Step 1: 编译项目确认无语法错误**

```bash
cd EnforcementApp && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

**Step 2: 如果有编译错误，修复并重新构建**

**Step 3: Commit fix if needed**

---

### Task 8: 部署到设备验证

**Step 1: 构建并安装 APK**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Step 2: 手动验证**

- 打开文件回放页面，已上传文件旁显示删除图标
- 点击删除图标 → 弹出确认对话框 → 确认后文件消失
- 点击"管理"按钮 → 已上传文件前出现 CheckBox，未上传的没有
- 勾选文件 → 底部"删除选中 (N)"按钮更新计数
- 点击"全选" → 所有已上传文件被选中
- 点击"删除选中" → 确认后文件被删除，退出管理模式
- 点击"取消" → 退出管理模式
