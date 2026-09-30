package com.rahulislam.facepsy.tasks.flower

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rahulislam.facepsy.R
import com.rahulislam.facepsy.data.TriggerContract
import com.rahulislam.facepsy.service.SensingService
import java.util.*

/**
 * Flower game on the 3x3 grid (sequence lengths 3–4). See `FlowerGameCommon.kt` for
 * the rules.
 *
 * Launched from [com.rahulislam.facepsy.MainActivity] to start a new session (new
 * [gameId], broadcasts a capture trigger), or from [Flower4x4Activity] after a loss at
 * length 5 (extras carry the session state).
 */
class Flower3x3Activity : AppCompatActivity(), View.OnClickListener {

    private val buttons = arrayOfNulls<ImageButton>(GRID_SIZE)
    private val indexByViewId = HashMap<Int, Int>()
    private var nextButton: Button? = null
    private var statusText: TextView? = null

    /** Time of the previous tap (or of the end of the glow sequence). */
    private var lastTapAt: Long = 0

    /** ms between consecutive taps in the current round. */
    private lateinit var timeDiff: LongArray
    private lateinit var tappedSequence: IntArray
    private var gameId = ""
    private var glowCount = 0
    private var correct = 0
    private var incorrect = 0

    /** Number of correct taps so far in the current round. */
    private var tapIndex = 0

    /** Number of taps (correct or not) so far in the current round. */
    private var tapCount = 0
    private lateinit var glowSequence: IntArray
    private var unixTime: Long? = null

    private var startGlowTime: Long? = null
    private var endGlowTime: Long? = null
    private lateinit var tapSeqTime: LongArray

    /** Taps are ignored until the glow sequence has finished playing. */
    private var flowerClickable: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flower3x3)

        bindFlowerButtons(buttons, indexByViewId, this)
        nextButton = findViewById(R.id.next3x3)
        statusText = findViewById(R.id.flowerStatusTv)

        unixTime = intent.getLongExtra(FlowerGame.EXTRA_UNIX, -1)
        val flag = intent.getIntExtra(FlowerGame.EXTRA_FLAG, -1)

        if (flag == -1) {
            // New session
            gameId = UUID.randomUUID().toString()
            glowCount = FlowerGame.INITIAL_GLOWS
            correct = 0
            incorrect = 0
            nextButton?.setText("start")
            nextButton?.setVisibility(View.VISIBLE)
            statusText?.setText("tap start to begin the task")
        } else {
            // Returning from the 4x4 grid after a loss
            gameId = intent.extras!!.getString(FlowerGame.EXTRA_GAME_ID)!!
            glowCount = intent.extras!!.getInt(FlowerGame.EXTRA_GLOWS)
            correct = intent.extras!!.getInt(FlowerGame.EXTRA_CORRECT)
            incorrect = intent.extras!!.getInt(FlowerGame.EXTRA_INCORRECT)
            nextButton?.setVisibility(View.VISIBLE)
            statusText?.setText("try again, tap next to continue")

            flowerClickable = true
        }

        val intent = Intent()
        intent.action = TriggerContract.ACTION_TRIGGER
        intent.putExtra(TriggerContract.EXTRA_PACKAGE_NAME, TriggerContract.TRIGGER_FLOWER_GAME)
        intent.putExtra(TriggerContract.EXTRA_DURATION, SensingService.triggerDuration[TriggerContract.DurationKeys.FLOWER_GAME].toString())
        intent.putExtra(TriggerContract.EXTRA_GAME_ID, gameId)
        this.sendBroadcast(intent)
    }

    /** Starts a round. Bound to the Next/Start button via `android:onClick` — do not rename. */
    fun newGame(view: View?) {
        flowerClickable = false
        nextButton?.setText("next")
        nextButton?.setVisibility(View.INVISIBLE)
        statusText?.setText("watch the flowers light up")
        tapIndex = 0
        tapCount = 0
        for (button in buttons) {
            button?.setImageResource(R.drawable.flower_blank)
            button?.setTag(false)
        }

        glowSequence = randomSequence(glowCount, GRID_SIZE)
        timeDiff = LongArray(glowCount)
        tappedSequence = IntArray(glowCount) { -1 }
        tapSeqTime = LongArray(glowCount) { -1 }

        startGlowTime = System.currentTimeMillis()
        playGlowSequence(buttons, glowSequence, statusText, TAG) {
            lastTapAt = System.currentTimeMillis()
            endGlowTime = lastTapAt
            flowerClickable = true
        }
    }

    private fun saveGameData(status: Boolean) {
        flowerClickable = false
        saveFlowerGameData(TAG, gameId, glowCount, NUM_SPAN, status, timeDiff, glowSequence,
                tappedSequence, startGlowTime, endGlowTime, tapSeqTime)
    }

    override fun onClick(v: View) {
        if (!flowerClickable) return

        val tappedAt = System.currentTimeMillis()
        val difference = tappedAt - lastTapAt
        lastTapAt = tappedAt
        timeDiff[tapCount] = difference
        tapCount++
        Log.d(TAG, "Difference : $difference")

        tappedSequence[tapIndex] = indexByViewId[v.id]!!
        tapSeqTime[tapIndex] = tappedAt

        // Tag is false until the flower has been tapped in this round
        if (v.tag.toString() == "false") {
            v.tag = true
            if (buttons[glowSequence[tapIndex]]!!.id == v.id) {
                Log.d(TAG, "OnClick : Correct!")
                buttons[glowSequence[tapIndex]]!!.setImageResource(R.drawable.flower_blue_tick)
                tapIndex++
                if (tapIndex == glowCount) {
                    // Whole sequence correct
                    correct++

                    Log.d(TAG, "Yippe : You win!")
                    Toast.makeText(this, "Yippe : You win!", Toast.LENGTH_SHORT).show()
                    saveGameData(true)
                    if (correct == FlowerGame.WINS_TO_FINISH || incorrect == FlowerGame.LOSSES_TO_FINISH) {
                        finish()
                    }
                    if (glowCount == FlowerGame.MAX_GLOWS_3X3) {
                        // Move to the 4x4 grid
                        glowCount++
                        val intent = Intent(this, Flower4x4Activity::class.java)
                        intent.putExtra(FlowerGame.EXTRA_GAME_ID, gameId)
                        intent.putExtra(FlowerGame.EXTRA_GLOWS, glowCount)
                        intent.putExtra(FlowerGame.EXTRA_CORRECT, correct)
                        intent.putExtra(FlowerGame.EXTRA_INCORRECT, incorrect)
                        intent.putExtra(FlowerGame.EXTRA_UNIX, unixTime)
                        intent.putExtra(FlowerGame.EXTRA_FLAG, 1)
                        startActivity(intent)
                        finish()
                    } else {
                        glowCount++
                        nextButton!!.visibility = View.VISIBLE
                        statusText!!.text = "correct, tap next to continue"
                    }
                }
            } else {
                incorrect++
                saveGameData(false)
                Log.d(TAG, "OnClick : Wrong!")
                v.findViewById<ImageButton>(v.id).setImageResource(R.drawable.flower_red_cross)
                if (correct == FlowerGame.WINS_TO_FINISH || incorrect == FlowerGame.LOSSES_TO_FINISH) {
                    finish()
                }

                if (glowCount != FlowerGame.INITIAL_GLOWS) {
                    glowCount--
                }
                nextButton!!.visibility = View.VISIBLE
                statusText!!.text = "try again, tap next to continue"
            }
        } else {
            Log.d(TAG, "OnClick : Wrong, Clicked before!")
            v.findViewById<ImageButton>(v.id).setImageResource(R.drawable.flower_red_cross)
        }
    }

    companion object {
        private const val TAG = "Flower3x3Activity"
        private const val GRID_SIZE = 9

        /** Grid width, stored as `num_span`. */
        private const val NUM_SPAN = 3
    }
}
