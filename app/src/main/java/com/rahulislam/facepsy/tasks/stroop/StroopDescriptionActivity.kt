package com.rahulislam.facepsy.tasks.stroop

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.rahulislam.facepsy.R

/** Instructions screen shown before [StroopActivity]; its Start button launches the task. */
class StroopDescriptionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stroop_description)

        val stroopStartBtn = findViewById<Button>(R.id.stroopStartBtn)
        stroopStartBtn.setOnClickListener {
            startActivity(Intent(this, StroopActivity::class.java))
        }
    }
}
