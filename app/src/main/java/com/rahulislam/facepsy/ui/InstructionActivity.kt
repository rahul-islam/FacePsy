package com.rahulislam.facepsy.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.rahulislam.facepsy.R

/** Static screen explaining the daily survey schedule and how to hold the phone. */
class InstructionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_instruction)
    }
}
