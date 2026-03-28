package com.hdcollection.enforcement.ui.playback

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.hdcollection.enforcement.R

class ImageViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        val path = intent.getStringExtra("path") ?: run { finish(); return }
        val imageView = findViewById<ImageView>(R.id.imageView)
        val bitmap = BitmapFactory.decodeFile(path)
        imageView.setImageBitmap(bitmap)

        // 点击关闭
        imageView.setOnClickListener { finish() }
    }
}
