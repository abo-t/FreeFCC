package com.freefcc.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * In-app UI language override: "" follows the system locale, "en" / "pl" force one.
 *
 * DJI controllers ship a trimmed system Settings, so the system language cannot be
 * relied on - the choice lives in the app's own SharedPreferences instead.
 * Every component that renders text (Activity, ViewModel, keepalive notification)
 * reads strings through [wrap], so all of them follow the same choice.
 */
object Lang {

    private const val PREFS = "freefcc"
    private const val KEY = "app_lang"

    /** Language codes offered in the UI, in display order. "" = system. */
    val OPTIONS = listOf("", "en", "pl")

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun set(context: Context, code: String) {
        require(code in OPTIONS) { "Unknown language code: $code" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
    }

    /** Returns [base] unchanged for the system language, or a context forced to the chosen locale. */
    fun wrap(base: Context): Context {
        val code = get(base)
        if (code.isEmpty()) return base
        val locale = Locale(code)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
