package com.freefcc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the aircraft profile choice: every offered profile maps to assets that
 * exist, the Lito X1 FCC profile keeps the frame order and cadence measured on
 * hardware (lmdegreeds/dji_fcc_gpsoff, doc/fcc-minimal-sequence.md) - 07:30
 * before 09:27, the reverse gave power and no 5.8 GHz, sent 8 times 1 s apart -
 * and each LED payload starts with the hash of the parameter name that model's
 * firmware indexes.
 *
 * Plain regex over the JSON: org.json is an Android stub in local unit tests.
 * Gradle runs them with app/ as the working directory, hence the relative paths.
 */
class AircraftProfileTest {

    private fun asset(name: String): String {
        val file = File("src/main/assets/profiles/$name")
        assertTrue("Missing asset $name (working dir: ${File(".").absolutePath})", file.exists())
        return file.readText()
    }

    private fun intField(json: String, key: String): Int =
        Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt()
            ?: error("No \"$key\" in profile")

    /** Frames as "s:i:d:payload", in file order. */
    private fun frames(json: String): List<String> =
        Regex("\\{\\s*\"s\"\\s*:\\s*(\\d+),\\s*\"i\"\\s*:\\s*(\\d+),\\s*\"d\"\\s*:\\s*(\\d+),\\s*\"p\"\\s*:\\s*\"([0-9a-fA-F]+)\"")
            .findAll(json).map { it.groupValues.drop(1).joinToString(":") }.toList()

    /**
     * DJI flight-controller parameter hash used by 03:F8/03:F9: name + "_0",
     * folded byte by byte modulo 0xFFFFFFFB, 4 bytes little-endian
     * (dji_fcc_gpsoff native/duml_core.cpp param_hash).
     */
    private fun paramHash(name: String): String {
        var h = 0L
        for (b in (name + "_0").toByteArray(Charsets.US_ASCII)) h = ((h shl 8) or b.toLong()) % 0xFFFFFFFBL
        return (0 until 4).joinToString("") { "%02x".format((h shr (8 * it)) and 0xFF) }
    }

    @Test
    fun everyProfileMapsToExistingAssets() {
        AircraftProfile.OPTIONS.forEach { code ->
            asset(AircraftProfile.fccAsset(code))
            asset(AircraftProfile.keepaliveAsset(code))
            asset(AircraftProfile.ledAsset(code, on = true))
            asset(AircraftProfile.ledAsset(code, on = false))
            AircraftProfile.altitudeAsset(code, unlock = true)?.let { asset(it) }
            AircraftProfile.altitudeAsset(code, unlock = false)?.let { asset(it) }
        }
    }

    /**
     * The Lito X1 altitude buttons write g_config.flying_limit.max_height - the
     * same hash and value the full fcc.json carries as its third frame (the one
     * the 2-frame Lito X1 FCC profile dropped). Universal has no button: its
     * Enable FCC already writes 500.
     */
    @Test
    fun litoX1AltitudeWritesMaxHeightLikeFrameThreeOfTheFullProfile() {
        val maxHeight = paramHash("g_config.flying_limit.max_height")
        assertEquals("8a237103", maxHeight)
        assertEquals(listOf("3:249:3:${maxHeight}f401"), frames(asset("altitude_500_lito_x1.json")))
        assertEquals(listOf("3:249:3:${maxHeight}7800"), frames(asset("altitude_120_lito_x1.json")))
        assertEquals("3:249:3:${maxHeight}f401", frames(asset("fcc.json"))[2])
        assertEquals(null, AircraftProfile.altitudeAsset(AircraftProfile.UNIVERSAL, unlock = true))
        assertEquals(null, AircraftProfile.altitudeAsset(AircraftProfile.UNIVERSAL, unlock = false))
    }

    @Test
    fun litoX1SendsChannelGroupThenSdrRegister() {
        assertEquals(
            listOf("7:48:9:41550000415500000100", "9:39:9:00024800ffff0200000000"),
            frames(asset("fcc_lito_x1.json"))
        )
    }

    @Test
    fun litoX1SendsEightRoundsOneSecondApart() {
        val json = asset("fcc_lito_x1.json")
        assertEquals(8, intField(json, "rounds"))
        assertEquals(1000, intField(json, "inter_round_delay_ms"))
    }

    @Test
    fun ledPayloadsAddressTheModelsParameterName() {
        val universal = paramHash("g_config.misc_cfg.forearm_lamp_ctrl")
        val lito = paramHash("forearm_led_ctrl")
        assertEquals(listOf("3:249:3:${universal}ef"), frames(asset("led_on.json")))
        assertEquals(listOf("3:249:3:${universal}00"), frames(asset("led_off.json")))
        assertEquals(listOf("3:249:3:${lito}ef"), frames(asset("led_on_lito_x1.json")))
        assertEquals(listOf("3:249:3:${lito}00"), frames(asset("led_off_lito_x1.json")))
    }

    @Test
    fun litoX1ParamWritesGoUnwrappedToTheInjectPort() {
        listOf(
            "led_on_lito_x1.json", "led_off_lito_x1.json",
            "altitude_500_lito_x1.json", "altitude_120_lito_x1.json"
        ).forEach { name ->
            val json = asset(name)
            assertEquals(40008, intField(json, "port"))
            assertTrue("$name must not be wrapped", !json.contains("\"wrapper\": true"))
        }
    }
}
