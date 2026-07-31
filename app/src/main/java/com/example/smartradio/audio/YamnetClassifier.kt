package com.example.smartradio.audio

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.channels.FileChannel

/**
 * Wraps Google's YAMNet TFLite model (521-class AudioSet classifier) and
 * reduces its output to a single "is this music?" verdict.
 *
 * SETUP REQUIRED: this project does not bundle the model weights (they're
 * ~4 MB and this build environment has no network access to fetch them).
 * Before building:
 *   1. Download yamnet.tflite from:
 *      https://tfhub.dev/google/lite-model/yamnet/classification/tflite/1
 *   2. Download the label map (yamnet_label_list.txt) from the same page's
 *      assets, one class name per line, 521 lines.
 *   3. Place both files in app/src/main/assets/
 */
class YamnetClassifier(context: Context) {

    private val interpreter: Interpreter
    private val labels: List<String>
    private val musicLabelIndices: Set<Int>
    private val speechLabelIndices: Set<Int>

    init {
        val assetManager = context.assets
        val fd = assetManager.openFd("yamnet.tflite")
        val modelBuffer = fd.createInputStream().channel.map(
            FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
        )
        interpreter = Interpreter(modelBuffer)

        labels = assetManager.open("yamnet_label_list.txt")
            .bufferedReader().readLines().map { it.trim() }

        // YAMNet's AudioSet ontology has a "Music" branch and a "Speech" branch;
        // we treat any label under those as the corresponding class. Matching by
        // substring keeps this robust to the exact label set without hardcoding
        // 521 indices.
        musicLabelIndices = labels.indices.filter { i ->
            val l = labels[i].lowercase()
            l.contains("music") || l.contains("song") || l.contains("singing")
        }.toSet()
        speechLabelIndices = labels.indices.filter { i ->
            val l = labels[i].lowercase()
            l.contains("speech") || l.contains("narration") || l.contains("conversation") ||
                l.contains("monologue") || l.contains("advertisement")
        }.toSet()
    }

    data class Verdict(val isMusic: Boolean, val musicScore: Float, val speechScore: Float)

    /** samples: mono audio at 16 kHz, range [-1, 1]. 15600 samples (~0.975s) is YAMNet's reference window. */
    fun classify(samples: FloatArray): Verdict {
        // YAMNet's input tensor has a dynamic length dimension, so resize it to
        // match exactly how many samples we're handing it this call.
        interpreter.resizeInput(0, intArrayOf(samples.size))
        interpreter.allocateTensors()

        // The published YAMNet .tflite has three outputs (scores, embeddings,
        // spectrogram); scores is output index 0 per its published signature.
        // Interpreter.run() only supports single-output models, so we use
        // runForMultipleInputsOutputs and only bother capturing scores.
        val scoresShape = interpreter.getOutputTensor(0).shape() // [numFrames, numClasses]
        val numFrames = scoresShape.getOrElse(0) { 1 }
        val numClasses = scoresShape.getOrElse(1) { labels.size }
        val scoresOutput = Array(numFrames) { FloatArray(numClasses) }

        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(samples), mapOf(0 to scoresOutput as Any))

        // Average across frames (a 0.975s window is normally exactly one frame).
        val avgScores = FloatArray(numClasses)
        for (frame in scoresOutput) {
            for (i in frame.indices) avgScores[i] += frame[i] / numFrames
        }

        val musicScore = musicLabelIndices.maxOfOrNull { avgScores.getOrElse(it) { 0f } } ?: 0f
        val speechScore = speechLabelIndices.maxOfOrNull { avgScores.getOrElse(it) { 0f } } ?: 0f

        return Verdict(
            isMusic = musicScore > speechScore && musicScore > MUSIC_CONFIDENCE_THRESHOLD,
            musicScore = musicScore,
            speechScore = speechScore
        )
    }

    fun close() = interpreter.close()

    companion object {
        private const val MUSIC_CONFIDENCE_THRESHOLD = 0.15f
    }
}
