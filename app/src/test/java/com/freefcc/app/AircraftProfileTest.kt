package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the FCC profile choice: every offered profile maps to assets that
 * exist, and the Lito X1 profile keeps the frame order and cadence that were
 * measured on hardware (lmdegreeds/dji_fcc_gpsoff, doc/fcc-minimal-sequence.md):
 * 07:30 before 09:27 - the reverse gave power and no 5.8 GHz - sent 8 times,
 * 1 s apart.
 *
 * Plain regex over the JSON: org.json is an Android stub in local unit tests.
 * Gradle runs them with app/ as the working directory, hence the relative paths.
 */
class FccProfileTest {

    private fun asset(name: String): String {
        val file = File("src/main/assets/profiles/$name")
        assertTrue("Missing asset $name (working dir: ${File(".").absolutePath})", file.exists())
        return file.readText()
    }

    private fun intField(json: String, key: String): Int =
        Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt()
            ?: error("No \"$key\" in profile")

    @Test
    fun everyProfileMapsToExistingAssets() {
        FccProfile.OPTIONS.forEach { code ->
            asset(FccProfile.applyAsset(code))
            asset(FccProfile.keepaliveAsset(code))
        }
    }

    @Test
    fun litoX1SendsChannelGroupThenSdrRegister() {
        val json = asset("fcc_lito_x1.json")
        val frames = Regex("\\{\\s*\"s\"\\s*:\\s*(\\d+),\\s*\"i\"\\s*:\\s*(\\d+),\\s*\"d\"\\s*:\\s*(\\d+),\\s*\"p\"\\s*:\\s*\"([0-9a-fA-F]+)\"")
            .findAll(json).map { it.groupValues.drop(1).joinToString(":") }.toList()
        assertEquals(
            listOf("7:48:9:41550000415500000100", "9:39:9:00024800ffff0200000000"),
            frames
        )
    }

    @Test
    fun litoX1SendsEightRoundsOneSecondApart() {
        val json = asset("fcc_lito_x1.json")
        assertEquals(8, intField(json, "rounds"))
        assertEquals(1000, intField(json, "inter_round_delay_ms"))
    }
}
