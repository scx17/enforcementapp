package com.hdcollection.enforcement.ui.playback

import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.hdcollection.enforcement.R
import java.io.File

class VideoPlayerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        val path = intent.getStringExtra("path") ?: run { finish(); return }
        val videoView = findViewById<VideoView>(R.id.videoView)
        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)
        videoView.setVideoURI(Uri.fromFile(File(path)))
        videoView.setOnPreparedListener { it.start() }
        videoView.setOnCompletionListener { finish() }
    }
}
