package com.hdcollection.enforcement.ui.worktask

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.data.AppSettings
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import timber.log.Timber

class WorkTaskListActivity : AppCompatActivity() {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var settings: AppSettings
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private val tasks = mutableListOf<WorkTask>()
    private lateinit var adapter: TaskAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_work_task_list)
        settings = AppSettings(getSharedPreferences("app_settings", MODE_PRIVATE))
        title = "作业工单"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = TaskAdapter()
        recyclerView.adapter = adapter
        loadTasks()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun loadTasks() {
        scope.launch {
            try {
                val url = "${settings.platformApiUrl}/api/work-task?assigneeDeviceId=${settings.deviceId}&pageSize=50"
                val result = withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url(url).get().build()).execute().body?.string()
                }
                if (result != null) {
                    val type = object : TypeToken<ApiResponse<List<WorkTask>>>() {}.type
                    val resp: ApiResponse<List<WorkTask>> = gson.fromJson(result, type)
                    if (resp.success && resp.data != null) {
                        tasks.clear()
                        // 只显示未完成的工单（完成/取消的不显示）
                        tasks.addAll(resp.data.filter { it.status != "completed" && it.status != "cancelled" })
                        adapter.notifyDataSetChanged()
                    }
                }
                emptyView.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
                recyclerView.visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
            } catch (e: Exception) {
                Timber.e(e, "加载工单失败")
                Toast.makeText(this@WorkTaskListActivity, "加载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun callApi(url: String, method: String = "POST", body: String = "{}") {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val rb = body.toRequestBody("application/json".toMediaType())
                    val req = when (method) {
                        "PUT" -> Request.Builder().url(url).put(rb).build()
                        else -> Request.Builder().url(url).post(rb).build()
                    }
                    client.newCall(req).execute()
                }
                Toast.makeText(this@WorkTaskListActivity, "操作成功", Toast.LENGTH_SHORT).show()
                loadTasks()
            } catch (e: Exception) {
                Timber.e(e, "操作失败")
                Toast.makeText(this@WorkTaskListActivity, "操作失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class TaskAdapter : RecyclerView.Adapter<TaskAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.taskTitle)
            val status: TextView = view.findViewById(R.id.taskStatus)
            val priority: TextView = view.findViewById(R.id.taskPriority)
            val content: TextView = view.findViewById(R.id.taskContent)
            val info: TextView = view.findViewById(R.id.taskInfo)
            val btnAccept: Button = view.findViewById(R.id.btnAccept)
            val btnStart: Button = view.findViewById(R.id.btnStart)
            val btnComplete: Button = view.findViewById(R.id.btnComplete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_work_task, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = tasks[position]
            holder.title.text = t.title
            holder.status.text = statusMap[t.status] ?: t.status
            holder.priority.text = priorityMap[t.priority] ?: t.priority
            holder.content.text = t.content ?: ""
            holder.content.visibility = if (t.content.isNullOrEmpty()) View.GONE else View.VISIBLE

            val infoParts = mutableListOf<String>()
            t.location?.let { if (it.isNotEmpty()) infoParts.add(it) }
            t.dueTime?.let { if (it.isNotEmpty()) infoParts.add("截止:${it.take(16)}") }
            t.readTime?.let { if (it.isNotEmpty()) infoParts.add("已阅:${it.take(16)}") }
            holder.info.text = infoParts.joinToString("  ")
            holder.info.visibility = if (infoParts.isEmpty()) View.GONE else View.VISIBLE

            holder.status.setTextColor(statusColor(t.status))
            holder.priority.setTextColor(priorityColor(t.priority))

            val base = settings.platformApiUrl
            // 已阅按钮隐藏（进入工单页面时已自动标记已阅）
            holder.btnAccept.visibility = View.GONE

            // 执行中按钮：pending/assigned 时显示
            val canStart = t.status == "assigned" || t.status == "pending"
            holder.btnStart.visibility = if (canStart) View.VISIBLE else View.GONE
            holder.btnStart.setOnClickListener { callApi("$base/api/work-task/${t.id}/start") }

            // 完成按钮：in_progress 时显示
            holder.btnComplete.visibility = if (t.status == "in_progress") View.VISIBLE else View.GONE
            holder.btnComplete.setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@WorkTaskListActivity)
                    .setTitle("完成工单")
                    .setMessage("确认完成「${t.title}」？\n完成后将从列表中移除。")
                    .setPositiveButton("确认完成") { _, _ ->
                        callApi("$base/api/work-task/${t.id}/complete")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        override fun getItemCount() = tasks.size
    }

    private fun statusColor(s: String) = when (s) {
        "pending" -> 0xFF909399.toInt(); "assigned" -> 0xFFE6A23C.toInt()
        "in_progress" -> 0xFF409EFF.toInt(); "completed" -> 0xFF67C23A.toInt()
        else -> 0xFFCCCCCC.toInt()
    }
    private fun priorityColor(p: String) = when (p) {
        "urgent" -> 0xFFF56C6C.toInt(); "high" -> 0xFFE6A23C.toInt()
        else -> 0xFF909399.toInt()
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }

    companion object {
        val statusMap = mapOf("pending" to "待处理", "assigned" to "已分配", "in_progress" to "执行中", "completed" to "已完成", "cancelled" to "已取消")
        val priorityMap = mapOf("urgent" to "紧急", "high" to "高", "normal" to "普通", "low" to "低")

        /** 标记所有未阅工单为已阅（弹窗点"阅读"时调用） */
        fun markAllAsRead(baseUrl: String, deviceId: String) {
            val client = OkHttpClient()
            val gson = Gson()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val url = "$baseUrl/api/work-task?assigneeDeviceId=$deviceId&pageSize=50"
                    val result = client.newCall(Request.Builder().url(url).get().build()).execute().body?.string() ?: return@launch
                    val type = object : TypeToken<ApiResponse<List<WorkTask>>>() {}.type
                    val resp: ApiResponse<List<WorkTask>> = gson.fromJson(result, type)
                    val unread = (resp.data ?: emptyList()).filter { it.readTime.isNullOrEmpty() && (it.status == "pending" || it.status == "assigned") }
                    for (task in unread) {
                        try {
                            val body = "{}".toRequestBody("application/json".toMediaType())
                            client.newCall(Request.Builder().url("$baseUrl/api/work-task/${task.id}/read").post(body).build()).execute()
                            Timber.i("自动标记已阅: taskId=${task.id}")
                        } catch (e: Exception) {
                            Timber.e(e, "标记已阅失败: taskId=${task.id}")
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "批量标记已阅失败")
                }
            }
        }

        /** 检查有无未阅/执行中工单（供 MainActivity 上线时调用） */
        fun checkPendingTasks(baseUrl: String, deviceId: String, callback: (unreadCount: Int, inProgressCount: Int) -> Unit) {
            val client = OkHttpClient()
            val gson = Gson()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val url = "$baseUrl/api/work-task?assigneeDeviceId=$deviceId&pageSize=50"
                    val result = client.newCall(Request.Builder().url(url).get().build()).execute().body?.string()
                    if (result != null) {
                        val type = object : TypeToken<ApiResponse<List<WorkTask>>>() {}.type
                        val resp: ApiResponse<List<WorkTask>> = gson.fromJson(result, type)
                        val tasks = resp.data ?: emptyList()
                        val unread = tasks.count { it.readTime.isNullOrEmpty() && (it.status == "pending" || it.status == "assigned") }
                        val inProgress = tasks.count { it.status == "in_progress" }
                        CoroutineScope(Dispatchers.Main).launch { callback(unread, inProgress) }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "检查待处理工单失败")
                }
            }
        }
    }
}

data class WorkTask(
    val id: Int = 0, val title: String = "", val content: String? = null,
    val status: String = "pending", val priority: String = "normal",
    val creatorId: String? = null, val creatorName: String? = null,
    val assigneeDeviceId: String? = null, val assigneeName: String? = null,
    val location: String? = null, val longitude: Double? = null, val latitude: Double? = null,
    val dueTime: String? = null, val readTime: String? = null, val startTime: String? = null,
    val completeTime: String? = null, val remark: String? = null
)

data class ApiResponse<T>(val success: Boolean = false, val data: T? = null, val total: Int = 0)
