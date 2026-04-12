package com.hdcollection.enforcement.ui.upload

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.hdcollection.enforcement.R

class ManualUploadActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_manual_upload)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, ManualUploadFragment())
                .commit()
        }
    }
}
