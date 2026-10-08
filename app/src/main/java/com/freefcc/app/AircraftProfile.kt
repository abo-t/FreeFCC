package com.freefcc.app

import android.content.Context
import androidx.annotation.StringRes

/**
 * Which FCC frame set the app sends: "universal" (fcc.json, 21 frames, the
 * upstream profile tested on Mini 4/5 Pro, Air 3S, Neo, Avata 360) or "lito_x1"
 * (fcc_lito_x1.json, 2 frames measured on RC 2 + Lito X1).
 *
 * The choice lives in the app's SharedPreferences like [Lang], so the Activity,
 * the ViewModel and the keepalive service all read the same value.
 */
object FccProfile {

    private const val PREFS = "freefcc"
    private const val KEY = "fcc_profile"

    const val UNIVERSAL = "universal"
    const val LITO_X1 = "lito_x1"

    /** Profile codes offered in the UI, in display order. */
    val OPTIONS = listOf(UNIVERSAL, LITO_X1)

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, UNIVERSAL) ?: UNIVERSAL

    fun set(context: Context, code: String) {
        require(code in OPTIONS) { "Unknown FCC profile: $code" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
    }

    /** Display name of a profile, shared by the selector and the log. */
    @StringRes
    fun label(code: String): Int = when (code) {
        UNIVERSAL -> R.string.fcc_profile_universal
        LITO_X1 -> R.string.fcc_profile_lito_x1
        else -> error("Unknown FCC profile: $code")
    }

    /** Asset sent by Enable FCC and Auto-FCC. */
    fun applyAsset(code: String): String = when (code) {
        UNIVERSAL -> "fcc.json"
        LITO_X1 -> "fcc_lito_x1.json"
        else -> error("Unknown FCC profile: $code")
    }

    /**
     * Asset the keepalive re-sends every tick (one round). Lito X1 re-sends its
     * own two frames: the universal keepalive frames (service mode + region +
     * commit) changed nothing on that aircraft in the measurements.
     */
    fun keepaliveAsset(code: String): String = when (code) {
        UNIVERSAL -> "fcc_keepalive.json"
        LITO_X1 -> "fcc_lito_x1.json"
        else -> error("Unknown FCC profile: $code")
    }
}
