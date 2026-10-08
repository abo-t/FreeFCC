package com.freefcc.app

import android.content.Context
import androidx.annotation.StringRes

/**
 * Which aircraft the app addresses, and so which frames it sends:
 *
 * - "universal" - the upstream profiles: fcc.json (21 frames, tested on Mini 4/5 Pro,
 *   Air 3S, Neo, Avata 360) and the LED write by the g_config.* parameter name on 40007.
 * - "lito_x1" - measured on RC 2 + Lito X1 by lmdegreeds/dji_fcc_gpsoff: fcc_lito_x1.json
 *   (2 frames), the LED write by the Lito X1 name forearm_led_ctrl on 40008, and the
 *   altitude limit write (max_height 500 / 120) on the same port.
 *
 * The choice lives in the app's SharedPreferences like [Lang], so the Activity,
 * the ViewModel and the keepalive service all read the same value.
 */
object AircraftProfile {

    private const val PREFS = "freefcc"
    // Key kept from the first release of this setting (1.6.0 test build), so a
    // controller that already picked Lito X1 keeps the choice.
    private const val KEY = "fcc_profile"

    const val UNIVERSAL = "universal"
    const val LITO_X1 = "lito_x1"

    /** Profile codes offered in the UI, in display order. */
    val OPTIONS = listOf(UNIVERSAL, LITO_X1)

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, UNIVERSAL) ?: UNIVERSAL

    fun set(context: Context, code: String) {
        require(code in OPTIONS) { "Unknown aircraft profile: $code" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
    }

    /** Display name of a profile, shared by the selector and the log. */
    @StringRes
    fun label(code: String): Int = when (code) {
        UNIVERSAL -> R.string.aircraft_profile_universal
        LITO_X1 -> R.string.aircraft_profile_lito_x1
        else -> error("Unknown aircraft profile: $code")
    }

    /** Asset sent by Enable FCC and Auto-FCC. */
    fun fccAsset(code: String): String = when (code) {
        UNIVERSAL -> "fcc.json"
        LITO_X1 -> "fcc_lito_x1.json"
        else -> error("Unknown aircraft profile: $code")
    }

    /**
     * Asset the keepalive re-sends every tick (one round). Lito X1 re-sends its
     * own two frames: the universal keepalive frames (service mode + region +
     * commit) changed nothing on that aircraft in the measurements.
     */
    fun keepaliveAsset(code: String): String = when (code) {
        UNIVERSAL -> "fcc_keepalive.json"
        LITO_X1 -> "fcc_lito_x1.json"
        else -> error("Unknown aircraft profile: $code")
    }

    /** Asset for LED ON / LED OFF. The parameter name, and so its hash, differs per model. */
    fun ledAsset(code: String, on: Boolean): String = when (code) {
        UNIVERSAL -> if (on) "led_on.json" else "led_off.json"
        LITO_X1 -> if (on) "led_on_lito_x1.json" else "led_off_lito_x1.json"
        else -> error("Unknown aircraft profile: $code")
    }

    /**
     * Asset for the altitude limit buttons (500 m / 120 m), or null when the profile
     * has no measured write for it - the card is then hidden. Lito X1 writes
     * g_config.flying_limit.max_height by hash on 40008: the frame the full fcc.json
     * carries as its third and fcc_lito_x1.json dropped (lmdegreeds dump on
     * RC 2 + Lito X1: 120 -> 500, persists across a DJI Fly relink). Universal has
     * none: fcc.json already writes 500 inside Enable FCC.
     */
    fun altitudeAsset(code: String, unlock: Boolean): String? = when (code) {
        UNIVERSAL -> null
        LITO_X1 -> if (unlock) "altitude_500_lito_x1.json" else "altitude_120_lito_x1.json"
        else -> error("Unknown aircraft profile: $code")
    }
}
