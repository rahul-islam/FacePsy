package com.rahulislam.facepsy.tasks.stroop

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.rahulislam.facepsy.MainActivity
import com.rahulislam.facepsy.R
import com.rahulislam.facepsy.data.FirebaseRefs
import com.rahulislam.facepsy.data.FirebaseRefs.Collections
import com.rahulislam.facepsy.data.TriggerContract
import com.rahulislam.facepsy.service.SensingService
import java.util.*

/**
 * Stroop color-word task.
 *
 * Shows a color word ([stroopWord]) drawn in a possibly different ink color
 * ([stroopColor]); the participant taps the button for the ink color. Each response
 * is written to Firestore `stroopData`. After `config/stroopTask.rounds` responses the
 * participant is returned to [MainActivity].
 *
 * Starting the task also broadcasts a capture trigger so the camera records the
 * participant's face while they play.
 */
class StroopActivity : AppCompatActivity(), View.OnClickListener {

    private lateinit var stroopColorTv: TextView

    /** Word shown on screen; one of [COLOR_NAMES]. */
    private var stroopWord = ""

    /** Ink color of the word; one of [COLOR_NAMES], also a color resource name. */
    private var stroopColor = ""
    private var stimulusShownAt: Long? = null
    private var stimulusRespondedAt: Long? = null
    private var gameId = ""

    /** Number of responses so far in this session. */
    private var seq: Int = 0
    private var stroopResponse: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stroop)

        stroopColorTv = findViewById(R.id.stroopColorTv)
        findViewById<Button>(R.id.redBtn).setOnClickListener(this)
        findViewById<Button>(R.id.greenBtn).setOnClickListener(this)
        findViewById<Button>(R.id.blueBtn).setOnClickListener(this)
        findViewById<Button>(R.id.yellowBtn).setOnClickListener(this)

        gameId = UUID.randomUUID().toString()
        seq = 0

        newGame()
        Log.i(TAG, "gameId: $gameId")
        val intent = Intent()
        intent.action = TriggerContract.ACTION_TRIGGER
        intent.putExtra(TriggerContract.EXTRA_PACKAGE_NAME, TriggerContract.TRIGGER_STROOP_TASK)
        intent.putExtra(TriggerContract.EXTRA_DURATION, SensingService.triggerDuration[TriggerContract.DurationKeys.STROOP_TASK].toString())
        intent.putExtra(TriggerContract.EXTRA_GAME_ID, gameId)
        this.sendBroadcast(intent)
    }

    /** Picks a random word and ink color and shows the next stimulus. */
    fun newGame() {
        stroopWord = COLOR_NAMES.shuffled().find { true }!!
        stroopColor = COLOR_NAMES.shuffled().find { true }!!

        stroopColorTv.text = stroopWord
        // Ink color is looked up by name: res/values/colors.xml defines RED, GREEN, BLUE, YELLOW.
        stroopColorTv.setTextColor(ContextCompat.getColor(this, resources.getIdentifier(stroopColor, "color", packageName)))
        stimulusShownAt = System.currentTimeMillis()
    }

    /** Writes the current stimulus and response to Firestore `stroopData`. */
    fun saveGameData() {
        val gameData = hashMapOf(
                "user_id" to FirebaseAuth.getInstance().currentUser?.uid,
                "game_id" to gameId,
                "stroopWord" to stroopWord,
                "stroopColor" to stroopColor,
                "stroopResponse" to stroopResponse,
                "stimulusShownAt" to stimulusShownAt,
                "stimulusRespondedAt" to stimulusRespondedAt,
                "seq" to seq,
                "timestamp" to SensingService.kronosClock.getCurrentTimeMs()
        )

        FirebaseRefs.firestore.collection(Collections.STROOP_DATA)
                .add(gameData)
                .addOnSuccessListener { documentReference ->
                    Log.d(TAG, "DocumentSnapshot added with ID: ${documentReference.id}")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Error adding document", e)
                }
    }

    override fun onClick(v: View?) {
        stimulusRespondedAt = System.currentTimeMillis()

        when (v!!.id) {
            R.id.redBtn -> stroopResponse = "RED"
            R.id.greenBtn -> stroopResponse = "GREEN"
            R.id.blueBtn -> stroopResponse = "BLUE"
            R.id.yellowBtn -> stroopResponse = "YELLOW"
        }

        seq++
        // NOTE: legacy behavior, see docs/known-issues.md — crashes if config/stroopTask
        // has not been loaded yet.
        if (seq < SensingService.stroopConfig["rounds"]!!.toInt()) {
            saveGameData()
            newGame()
        } else {
            saveGameData()

            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(i)
        }
    }

    companion object {
        const val TAG = "StroopActivity"

        /** Stroop colors; must match the color resource names in colors.xml. */
        private val COLOR_NAMES = listOf("RED", "GREEN", "BLUE", "YELLOW")
    }
}
