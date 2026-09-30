package com.rahulislam.facepsy.tasks.flower

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth
import com.rahulislam.facepsy.R
import com.rahulislam.facepsy.data.FirebaseRefs
import com.rahulislam.facepsy.data.FirebaseRefs.Collections
import com.rahulislam.facepsy.service.SensingService

/*
 * Code shared by the Flower (visual-spatial memory) game screens.
 *
 * Game flow: flowers light up one after another; the participant must tap them in the
 * same order. The sequence length ("glows", stored as `complexity`) grows by one after
 * a win and shrinks by one after a loss. Lengths 3–4 are played on the 3x3 grid
 * ([Flower3x3Activity]) and 5–7 on the 4x4 grid ([Flower4x4Activity]). The session
 * ends after [FlowerGame.WINS_TO_FINISH] wins or [FlowerGame.LOSSES_TO_FINISH] losses.
 */

object FlowerGame {
    /** Sequence length of the first round. */
    const val INITIAL_GLOWS = 3

    /** Longest sequence on the 3x3 grid; winning it moves to the 4x4 grid. */
    const val MAX_GLOWS_3X3 = 4

    /** Shortest sequence on the 4x4 grid; losing it moves back to the 3x3 grid. */
    const val MIN_GLOWS_4X4 = 5

    /** Longest sequence; it is not increased further. */
    const val MAX_GLOWS_4X4 = 7

    const val WINS_TO_FINISH = 5
    const val LOSSES_TO_FINISH = 2

    /** How long each flower stays lit, and the pause before it. */
    const val GLOW_STEP_MS = 1000L

    /** Pause before switching from the 4x4 grid back to 3x3. */
    const val GRID_SWITCH_DELAY_MS = 2000L

    // Intent extras passed between the two grid activities.
    const val EXTRA_GAME_ID = "gameId"
    const val EXTRA_GLOWS = "glows"
    const val EXTRA_CORRECT = "correct"
    const val EXTRA_INCORRECT = "incorrect"
    const val EXTRA_UNIX = "unix"
    const val EXTRA_FLOWER_DATA = "flowerData"

    /** Present (any value but -1) when a grid activity is started by the other grid. */
    const val EXTRA_FLAG = "flag"
}

/**
 * Returns [n] distinct random button indices in `[0, gridSize)`.
 * [n] must not exceed [gridSize].
 */
internal fun randomSequence(n: Int, gridSize: Int): IntArray {
    val sequence = IntArray(n)
    var i = 0
    while (i < sequence.size) {
        // (Math.random() * (gridSize + 1)).toInt() is in [0, gridSize]; gridSize is rejected below.
        sequence[i] = (Math.random() * (gridSize + 1)).toInt()
        if (sequence[i] == gridSize) {
            continue
        }
        for (j in 0 until i) {
            if (sequence[i] == sequence[j] || sequence[j] == gridSize) {
                i-- // duplicate: draw index i again
                break
            }
        }
        i++
    }
    return sequence
}

/**
 * Finds the grid buttons `button_0` … `button_{count-1}` by resource name, sets
 * [listener] on them, and records each button's view id → grid index in [indexByViewId].
 */
internal fun Activity.bindFlowerButtons(
        buttons: Array<ImageButton?>,
        indexByViewId: HashMap<Int, Int>,
        listener: View.OnClickListener
) {
    for (i in buttons.indices) {
        val resID = resources.getIdentifier("button_$i", "id", packageName)
        buttons[i] = findViewById(resID)
        buttons[i]?.setOnClickListener(listener)
        indexByViewId[resID] = i
    }
}

/**
 * On a background thread, lights up the flowers in [glowSequence] one at a time, then
 * shows the "tap in the previous sequence" prompt and calls [onFinished] (still on the
 * background thread).
 */
internal fun Activity.playGlowSequence(
        buttons: Array<ImageButton?>,
        glowSequence: IntArray,
        statusText: TextView?,
        tag: String,
        onFinished: () -> Unit
) {
    Thread {
        for (buttonIndex in glowSequence) {
            SystemClock.sleep(FlowerGame.GLOW_STEP_MS)
            runOnUiThread {
                Log.d(tag, "finalI : $buttonIndex")
                buttons[buttonIndex]?.setImageResource(R.drawable.flower_blue)
            }
            SystemClock.sleep(FlowerGame.GLOW_STEP_MS)
            runOnUiThread {
                Log.d(tag, "finalI : $buttonIndex")
                buttons[buttonIndex]?.setImageResource(R.drawable.flower_blank)
            }
        }
        statusText?.post { statusText.setText("tap in the previous sequence") }
        onFinished()
    }.start()
}

/**
 * Writes one Flower game round to Firestore `flowerGameData`.
 *
 * @param numSpan grid width (3 or 4).
 * @param status true if the whole sequence was tapped correctly.
 * @param timeDiff ms between consecutive taps (first entry: since the sequence ended).
 */
internal fun saveFlowerGameData(
        tag: String,
        gameId: String,
        glowCount: Int,
        numSpan: Int,
        status: Boolean,
        timeDiff: LongArray,
        targetSequence: IntArray,
        tappedSequence: IntArray,
        startGlowTime: Long?,
        endGlowTime: Long?,
        tapSeqTime: LongArray
) {
    val gameData = hashMapOf(
            "user_id" to FirebaseAuth.getInstance().currentUser?.uid,
            "game_id" to gameId,
            "complexity" to glowCount,
            "num_span" to numSpan,
            "status" to status,
            "time_diff" to timeDiff.toList(),
            "target_seq" to targetSequence.toList(),
            "tapped_seq" to tappedSequence.toList(),
            "startGlowTime" to startGlowTime,
            "endGlowTime" to endGlowTime,
            "tapSeqTime" to tapSeqTime.toList(),
            "timestamp" to SensingService.kronosClock.getCurrentTimeMs()
    )

    FirebaseRefs.firestore.collection(Collections.FLOWER_GAME_DATA)
            .add(gameData)
            .addOnSuccessListener { documentReference ->
                Log.d(tag, "DocumentSnapshot added with ID: ${documentReference.id}")
            }
            .addOnFailureListener { e ->
                Log.w(tag, "Error adding document", e)
            }
}
