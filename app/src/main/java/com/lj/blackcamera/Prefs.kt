package com.lj.blackcamera

import android.content.Context
import androidx.core.content.edit

enum class Lens(val label: String) { BACK("Back"), FRONT("Front") }

enum class Quality(val label: String, val width: Int, val height: Int) {
    SD("480p", 640, 480), HD("720p", 1280, 720), FHD("1080p", 1920, 1080)
}

/** Which way the picture is turned. [surface] is an android.view.Surface rotation. */
enum class Turn(val label: String, val surface: Int) {
    LANDSCAPE("Landscape", 1), PORTRAIT("Portrait", 0), LANDSCAPE_FLIP("Landscape ⟲", 3), PORTRAIT_FLIP("Upside down", 2);

    val portrait get() = surface == 0 || surface == 2
}

/** Persisted settings. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("camera", Context.MODE_PRIVATE)

    const val DEFAULT_PORT = 8080

    fun port(c: Context) = sp(c).getInt("port", DEFAULT_PORT)
    fun setPort(c: Context, port: Int) = sp(c).edit { putInt("port", port) }

    fun lens(c: Context) = Lens.entries.firstOrNull { it.name == sp(c).getString("lens", null) } ?: Lens.BACK
    fun setLens(c: Context, lens: Lens) = sp(c).edit { putString("lens", lens.name) }

    fun quality(c: Context) = Quality.entries.firstOrNull { it.name == sp(c).getString("quality", null) } ?: Quality.HD
    fun setQuality(c: Context, q: Quality) = sp(c).edit { putString("quality", q.name) }

    fun turn(c: Context) = Turn.entries.firstOrNull { it.name == sp(c).getString("turn", null) } ?: Turn.LANDSCAPE
    fun setTurn(c: Context, t: Turn) = sp(c).edit { putString("turn", t.name) }

    /** JPEG quality, 1-100. */
    fun jpeg(c: Context) = sp(c).getInt("jpeg", 80)
}
