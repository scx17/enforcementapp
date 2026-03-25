package com.hdcollection.enforcement.ui.notification

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hdcollection.enforcement.EnforcementApp
import com.hdcollection.enforcement.R
import com.hdcollection.enforcement.notification.PlatformNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationListActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_list)

        val app = application as EnforcementApp
        val notifications = app.notificationService.notifications.toList()

        val recycler = findViewById<RecyclerView>(R.id.recyclerNotifications)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = NotificationAdapter(notifications)
    }
}

class NotificationAdapter(
    private val items: List<PlatformNotification>
) : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_notification, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvMessage.text = item.message
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        holder.tvTime.text = sdf.format(Date(item.receivedAt))
    }
}
