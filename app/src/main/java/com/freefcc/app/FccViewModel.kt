package com.freefcc.app

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Severity of a log entry or status line; the UI maps it to a colour. */
enum class Tone { INFO, BUSY, OK, ERROR }

/** One activity-log line. [time] is HH:mm:ss, [text] is already localized. */
data class LogEntry(val time: String, val text: String, val tone: Tone) {
    override fun toString() = "[$time] $text"
}

/**
 * Immutable UI state for the entire app.
 *
 * The ViewModel updates this via copy() and the Compose layer observes it
 * with collectAsStateWithLifecycle(). Every field here represents something
 * the UI needs to render.
 */
data class AppState(
    val status: String = "idle",
    val message: String = "",
    val isConnected: Boolean = false,
    val isFccEnabled: Boolean = false,
    val is4gBusy: Boolean = false,
    val fourGMessage: String = "",
    val isBusy: Boolean = false,
    val isHardwareBusy: Boolean = false,
    val busyProgress: Float = 0f,
    val aircraftSerial: String = "",
    val manualSerial: String = "",
    val isProbingSerial: Boolean = false,
    val controllerModel: String = "",
    val deviceInfo: String = "",
    val isQueryingInfo: Boolean = false,
    val autoFcc: Boolean = false,
    val isLedBusy: Boolean = false,
    val ledStatus: String = "",
    val ledTone: Tone = Tone.INFO,
    val isAltitudeBusy: Boolean = false,
    val altitudeStatus: String = "",
    val altitudeTone: Tone = Tone.INFO,
    val logMessages: List<LogEntry> = emptyList(),
    val isExportingLog: Boolean = false,
    val language: String = "",
    val aircraftProfile: String = AircraftProfile.UNIVERSAL,
    // Update state
    val updateInfo: UpdateInfo? = null,
    val updateNoRelease: Boolean = false,
    val isCheckingUpdate: Boolean = false,
    val isDownloadingUpdate: Boolean = false,
    val updateDownloadProgress: Float = 0f,
    val isUpdateDownloaded: Boolean = false,
    val updateAvailable: Boolean = false,
    val updateChecked: Boolean = false,
    // Keepalive state
    val isKeepaliveRunning: Boolean = false
)

/**
 * Manages all app state and business logic.
 *
 * The UI never touches the transport layer directly. It calls methods on
 * this ViewModel, which runs operations on a background thread (Dispatchers.IO)
 * and updates the observable [state] flow. The UI reacts to state changes
 * automatically via Compose's collectAsStateWithLifecycle().
 *
 * Every user-visible string comes from the strings.xml resources through [s],
 * which honours the in-app language choice ([Lang]).
 *
 * @param app The Application context, used for SharedPreferences and asset loading
 */
class FccViewModel(private val app: Application) : AndroidViewModel(app) {

    companion object {
        /**
         * Aircraft model codes *hinted* to support the DJI Cellular Dongle 2 / 4G.
         *
         * This is ADVISORY ONLY - it is not a hard gate. Two reasons:
         *  1. It cannot be applied reliably. probeSerial() usually returns the
         *     full 1581… factory serial, which does not contain a W[AM]xxx model
         *     code at all, so there is nothing here to match against.
         *  2. The codes themselves are uncertain. Public sources disagree on what
         *     wa233/wa234 map to, and DJI ships the Cellular Dongle 2 for the
         *     Mini 4 Pro (wa140) with a mounting kit - contrary to the old
         *     assumption that the Mini series has no cellular option.
         *     Source: DJI Cellular Dongle 2 listed compatibility - Air 3,
         *     Air 3S, Mini 4 Pro, Matrice 4T, Matrice 4E.
         *
         * The authoritative "can this aircraft do 4G" signal is
         * DumlTransport.is4gDonglePresent(): if the /duss/mb/0x205 socket is
         * connectable, a cellular module is attached. That is what actually
         * gates activation. This set only lets us log a helpful note.
         */
        private val MODELS_WITH_4G = setOf("wa341", "wa233", "wa234", "wm630", "wa140")
    }

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val transport = DumlTransport()
    private val prefs = app.getSharedPreferences("freefcc", Context.MODE_PRIVATE)

    /** Context carrying the chosen UI language; rebuilt by [setLanguage]. */
    private var res: Context = Lang.wrap(app)

    /** init() runs once per ViewModel; Activity re-creation must not re-trigger Auto-FCC. */
    private var initialized = false

    init {
        // MainActivity.onCreate() calls init() below on every Activity re-creation
        // (e.g. config change), but this class init{} runs exactly once per
        // ViewModel instance - the collector must live here, not in init().
        viewModelScope.launch {
            HardwareLock.busy.collect { busy -> update { copy(isHardwareBusy = busy) } }
        }
        // Restore the cached aircraft serial from a previous session so the
        // user does not have to re-probe before 4G if the drone is the same.
        // A manually-entered serial takes priority and is shown in the field.
        val manual = prefs.getString("manual_aircraft_sn", "").orEmpty()
        val cachedSerial = prefs.getString("aircraft_serial", "").orEmpty()
        val shown = if (manual.isNotEmpty()) manual else cachedSerial
        update { copy(manualSerial = manual, aircraftSerial = shown, language = Lang.get(app), aircraftProfile = AircraftProfile.get(app)) }
    }

    /**
     * Stores (or clears) a manually-entered aircraft serial. A manual serial
     * takes priority over auto-detection everywhere - the reliable fallback
     * when the controller never surfaces the serial on its own.
     */
    fun setManualSerial(serial: String) {
        val s = serial.trim().uppercase()
        prefs.edit().putString("manual_aircraft_sn", s).apply()
        if (s.isEmpty()) {
            update { copy(manualSerial = "") }
            log(s(R.string.log_manual_cleared))
        } else {
            update { copy(manualSerial = s, aircraftSerial = s) }
            log(
                if (DumlTransport.isValidSerial(s)) s(R.string.log_manual_set, s)
                else s(R.string.log_manual_set_unusual, s),
                Tone.OK
            )
        }
    }

    /** Claims the shared hardware lock for one operation. Returns false if another (including the keepalive service) is already running. */
    private fun beginHardwareOp(): Boolean = HardwareLock.tryBegin()

    /** Releases the shared hardware lock. Must run in a finally block covering every exit path. */
    private fun endHardwareOp() = HardwareLock.end()

    fun init() {
        if (initialized) return
        initialized = true

        val model = try { Build.DEVICE } catch (_: Exception) { "unknown" }
        val autoEnabled = prefs.getBoolean("auto_fcc", false)
        // Sync the keepalive toggle with the persistent flag so the UI is
        // correct after a process restart (e.g. low-memory kill + sticky restart).
        val keepaliveRunning = FccKeepaliveService.isRunningFlagSet(app)
        update { copy(controllerModel = model, status = "disconnected", autoFcc = autoEnabled, isKeepaliveRunning = keepaliveRunning) }

        if (autoEnabled) {
            log(s(R.string.log_auto_fcc_starting), Tone.BUSY)
            autoConnectAndApply()
        }

        checkForUpdates()
    }

    // --- Language ---

    /**
     * Saves the in-app language ("" = system, "en", "pl"). The caller recreates
     * the Activity so Compose re-reads its strings; entries already in the log
     * keep the language they were written in.
     */
    fun setLanguage(code: String) {
        Lang.set(app, code)
        res = Lang.wrap(app)
        update { copy(language = code) }
    }

    // --- FCC profile ---

    /**
     * Saves the aircraft profile ([AircraftProfile]: FCC and LED frames). A running
     * keepalive is restarted so its next tick already sends the new profile's frames.
     */
    fun setAircraftProfile(code: String) {
        AircraftProfile.set(app, code)
        update { copy(aircraftProfile = code) }
        log(s(R.string.log_aircraft_profile_set, s(AircraftProfile.label(code))))
        if (_state.value.isKeepaliveRunning) FccKeepaliveService.start(app)
    }

    // --- Auto-FCC ---

    /**
     * Toggles auto-FCC on or off. When enabled, the app will automatically
     * connect to the controller and apply FCC mode every time it launches.
     * The setting is saved to SharedPreferences and persists across restarts.
     */
    fun toggleAutoFcc() {
        val newValue = !_state.value.autoFcc
        prefs.edit().putBoolean("auto_fcc", newValue).apply()
        update { copy(autoFcc = newValue) }
        log(s(if (newValue) R.string.log_auto_fcc_on else R.string.log_auto_fcc_off))
    }

    /**
     * Connects to the controller and applies FCC mode automatically.
     * Waits for connection, then sends the FCC profile, starts the keepalive
     * service, and launches DJI Fly.
     */
    private fun autoConnectAndApply() {
        if (!beginHardwareOp()) {
            log(s(R.string.log_auto_skipped_busy), Tone.ERROR)
            return
        }
        runOnIO {
            try {
                // Wait a moment for the UI to render
                delay(1000)

                // Try to connect - scans all known ports
                update { copy(status = "connecting", message = s(R.string.msg_auto_connecting)) }
                if (!transport.connect()) {
                    log(s(R.string.log_auto_not_found), Tone.ERROR)
                    update { copy(status = "disconnected", message = s(R.string.msg_auto_not_found)) }
                    return@runOnIO
                }

                log(s(R.string.log_auto_connected), Tone.OK)
                val detectedPort = transport.getDetectedPort()
                if (detectedPort > 0) {
                    log(s(R.string.log_port_detected, detectedPort))
                }
                val serial = transport.probeSerial(1500)
                if (serial.isNotEmpty()) {
                    prefs.edit().putString("aircraft_serial", serial).apply()
                }
                update {
                    copy(
                        status = "connected",
                        isConnected = true,
                        aircraftSerial = serial,
                        message = s(R.string.msg_auto_connected_applying)
                    )
                }
                if (serial.isNotEmpty()) log(s(R.string.log_aircraft_serial, serial))

                // Apply FCC
                delay(500)
                update { copy(status = "applying", isBusy = true, busyProgress = 0f, message = s(R.string.msg_applying_fcc)) }
                log(s(R.string.log_auto_applying), Tone.BUSY)

                val profile = Profiles.load(app, AircraftProfile.fccAsset(AircraftProfile.get(app)))
                val success = transport.sendFrames(
                    frames = profile.frames,
                    rounds = profile.rounds,
                    interFrameDelayMs = profile.interFrameDelay,
                    interRoundDelayMs = profile.interRoundDelay,
                    readWindowMs = profile.readWindowMs,
                    port = profile.port
                ) { progress -> update { copy(busyProgress = progress) } }

                if (success) {
                    update {
                        copy(
                            status = "fcc_enabled",
                            message = s(R.string.msg_auto_fcc_enabled_keepalive),
                            isFccEnabled = true,
                            isBusy = false,
                            busyProgress = 1f,
                            isConnected = true
                        )
                    }
                    log(s(R.string.log_auto_fcc_enabled), Tone.OK)

                    // Auto-start keepalive
                    delay(500)
                    update { copy(isKeepaliveRunning = true) }
                    FccKeepaliveService.start(app)
                    log(s(R.string.log_auto_keepalive_started), Tone.OK)

                    // Auto-launch DJI Fly
                    delay(500)
                    update { copy(message = s(R.string.msg_launching_fly)) }
                    log(s(R.string.log_auto_launching_fly))
                    launchDjiFly()
                } else {
                    update {
                        copy(
                            status = "connected",
                            message = s(R.string.msg_auto_failed),
                            isBusy = false,
                            busyProgress = 0f
                        )
                    }
                    log(s(R.string.log_auto_failed), Tone.ERROR)
                }
            } catch (e: Exception) {
                val text = s(R.string.auto_error, e.message.orEmpty())
                log(text, Tone.ERROR)
                update { copy(status = "disconnected", message = text, isBusy = false, busyProgress = 0f) }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- Connection ---

    /**
     * Connects to the DUML proxy, auto-detecting the correct port.
     * Probes for the aircraft serial number after connecting.
     */
    fun connect() {
        if (!beginHardwareOp()) {
            log(s(R.string.log_hw_busy), Tone.ERROR)
            return
        }
        update { copy(status = "connecting", message = s(R.string.msg_connecting)) }
        log(s(R.string.msg_connecting))

        runOnIO {
            try {
                if (transport.connect()) {
                    log(s(R.string.log_controller_connected), Tone.OK)
                    val detectedPort = transport.getDetectedPort()
                    if (detectedPort > 0) {
                        log(s(R.string.log_port_detected, detectedPort))
                    }
                    val serial = transport.probeSerial(1500)
                    if (serial.isNotEmpty()) {
                        prefs.edit().putString("aircraft_serial", serial).apply()
                    }
                    update {
                        copy(
                            status = "connected",
                            message = if (serial.isNotEmpty()) s(R.string.msg_connected_serial, serial) else s(R.string.msg_connected_ready),
                            isConnected = true,
                            aircraftSerial = serial
                        )
                    }
                    if (serial.isNotEmpty()) log(s(R.string.log_aircraft_serial, serial))
                } else {
                    update {
                        copy(
                            status = "disconnected",
                            message = s(R.string.msg_controller_not_found),
                            isConnected = false
                        )
                    }
                    log(s(R.string.log_connect_failed), Tone.ERROR)
                }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- FCC ---

    /**
     * Sends the FCC unlock profile chosen in [AircraftProfile]: fcc.json (21 frames,
     * 2 rounds) or fcc_lito_x1.json (2 frames, 8 rounds 1 s apart). Frames,
     * rounds and timing all come from the JSON asset.
     */
    fun enableFcc() {
        if (!beginHardwareOp()) {
            log(s(R.string.log_hw_busy), Tone.ERROR)
            return
        }
        update { copy(status = "applying", isBusy = true, busyProgress = 0f, message = s(R.string.msg_enabling_fcc)) }
        log(s(R.string.msg_enabling_fcc), Tone.BUSY)

        runOnIO {
            try {
                val profile = Profiles.load(app, AircraftProfile.fccAsset(AircraftProfile.get(app)))
                log(s(R.string.log_fcc_profile_loaded, profile.frames.size, profile.rounds), Tone.BUSY)

                val success = transport.sendFrames(
                    frames = profile.frames,
                    rounds = profile.rounds,
                    interFrameDelayMs = profile.interFrameDelay,
                    interRoundDelayMs = profile.interRoundDelay,
                    readWindowMs = profile.readWindowMs,
                    port = profile.port
                ) { progress -> update { copy(busyProgress = progress) } }

                if (success) {
                    update {
                        copy(
                            status = "fcc_enabled",
                            message = s(R.string.msg_fcc_enabled),
                            isFccEnabled = true,
                            isBusy = false,
                            busyProgress = 1f,
                            isConnected = true
                        )
                    }
                    log(s(R.string.log_fcc_enabled, profile.frames.size), Tone.OK)
                } else {
                    update {
                        copy(
                            status = "connected",
                            message = s(R.string.msg_fcc_failed),
                            isBusy = false,
                            busyProgress = 0f
                        )
                    }
                    log(s(R.string.log_fcc_failed), Tone.ERROR)
                }
            } catch (e: Exception) {
                val text = s(R.string.fcc_error, e.message.orEmpty())
                log(text, Tone.ERROR)
                update { copy(status = "connected", message = text, isBusy = false, busyProgress = 0f) }
            } finally {
                endHardwareOp()
            }
        }
    }

    /** Sends the CE restore command: a single frame that resets to factory region. */
    fun disableFcc() {
        if (!beginHardwareOp()) {
            log(s(R.string.log_hw_busy), Tone.ERROR)
            return
        }
        // Stop keepalive first - otherwise it re-applies FCC 2 seconds after
        // we restore CE, undoing the user's intent.
        if (_state.value.isKeepaliveRunning) {
            stopKeepalive()
        }
        update { copy(status = "restoring", isBusy = true, busyProgress = 0f, message = s(R.string.msg_restoring_ce)) }
        log(s(R.string.msg_restoring_ce), Tone.BUSY)

        runOnIO {
            try {
                val profile = Profiles.load(app, "ce_restore.json")
                val success = transport.sendFrames(
                    frames = profile.frames,
                    rounds = profile.rounds,
                    readWindowMs = profile.readWindowMs
                )

                if (success) {
                    update { copy(status = "connected", message = s(R.string.msg_ce_restored), isFccEnabled = false, isBusy = false) }
                    log(s(R.string.msg_ce_restored), Tone.OK)
                    // ce_restore.json resets the region, not the SDR register the
                    // Lito X1 profile writes; a full aircraft power cycle drops both.
                    if (AircraftProfile.get(app) == AircraftProfile.LITO_X1) log(s(R.string.log_ce_lito_power_cycle))
                } else {
                    update { copy(status = "connected", message = s(R.string.msg_ce_failed), isBusy = false) }
                    log(s(R.string.msg_ce_failed), Tone.ERROR)
                }
            } catch (e: Exception) {
                val text = s(R.string.ce_error, e.message.orEmpty())
                log(text, Tone.ERROR)
                update { copy(status = "connected", message = text, isBusy = false) }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- FCC Keepalive ---

    /**
     * Starts a foreground service that re-applies the FCC profile every 2 seconds.
     * This prevents DJI Fly from resetting the radio back to CE mode when it
     * connects to the drone. The service runs independently of the Activity
     * lifecycle so it keeps working when the user switches to DJI Fly.
     */
    fun startKeepalive() {
        if (_state.value.isKeepaliveRunning) {
            log(s(R.string.log_keepalive_already))
            return
        }
        update { copy(isKeepaliveRunning = true) }
        FccKeepaliveService.start(app)
        log(s(R.string.log_keepalive_started), Tone.OK)
    }

    /** Stops the keepalive foreground service. */
    fun stopKeepalive() {
        FccKeepaliveService.stop(app)
        update { copy(isKeepaliveRunning = false) }
        log(s(R.string.log_keepalive_stopped))
    }

    // --- Launch DJI Fly ---

    /**
     * Launches the DJI Fly app (dji.go.v5) so the user can continue flying
     * with FCC mode active. The keepalive service keeps re-applying FCC in the
     * background while DJI Fly runs.
     */
    fun launchDjiFly() {
        val pm = app.packageManager
        // Try the standard launch intent first
        var intent = pm.getLaunchIntentForPackage("dji.go.v5")
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
                log(s(R.string.log_launched_fly), Tone.OK)
                return
            } catch (_: Exception) {}
        }

        // Fallback: try explicit component - DJI Fly's main activity
        for (activityName in listOf(
            "dji.pilot2.lite.LauncherActivity",
            "dji.go.v5.MainActivity",
            "dji.pilot2.lite.LiteLauncherActivity",
            "dji.go.v5.SplashActivity"
        )) {
            val explicitIntent = android.content.Intent().apply {
                component = android.content.ComponentName("dji.go.v5", activityName)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                app.startActivity(explicitIntent)
                log(s(R.string.log_launched_fly), Tone.OK)
                return
            } catch (_: Exception) {}
        }

        // Fallback 2: try dji.go.v4
        intent = pm.getLaunchIntentForPackage("dji.go.v4")
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
                log(s(R.string.log_launched_go4), Tone.OK)
                return
            } catch (_: Exception) {}
        }

        log(s(R.string.log_fly_missing), Tone.ERROR)
    }

    // --- 4G ---

    /**
     * Sends the 128-frame 4G activation profile.
     * The aircraft serial is embedded in each frame's payload at runtime.
     * 4G frames are sent via Unix domain socket (/duss/mb/0x205), not TCP.
     *
     * The socket does not respond, so this can only confirm the frames were
     * written - never confirm the aircraft actually activated 4G. There is
     * no "off" action: no send-only command exists to reliably deactivate it.
     *
     * Guards (fail fast on the common failure modes, but do not over-block):
     * 1. Aircraft serial must be present - it is embedded in every 4G payload.
     *    We do not reject on a specific length: the probe may return either the
     *    full 1581… factory serial or a short W[AM]xxx model code, and both are
     *    valid inputs to the profile builder.
     * 2. The 4G dongle must be present - this is the AUTHORITATIVE gate. If the
     *    abstract socket `/duss/mb/0x205` is not connectable, no cellular module
     *    is attached and no frame can succeed, so we stop before writing 128.
     * 3. Model code is only an advisory note. A full 1581… serial carries no
     *    model code to check, and the code list is not reliable enough to block
     *    on (DJI ships the Cellular Dongle 2 for the Mini 4 Pro too). If the
     *    dongle is attached, we proceed regardless of model.
     */
    fun send4gActivationFrames() {
        if (!beginHardwareOp()) {
            log(s(R.string.log_hw_busy), Tone.ERROR)
            return
        }
        update { copy(is4gBusy = true, busyProgress = 0f, fourGMessage = "") }
        log(s(R.string.log_4g_sending), Tone.BUSY)

        runOnIO {
            try {
                val serial = getOrProbeSerial()

                // Guard 1: we need *some* serial to embed in the payload.
                if (serial.isEmpty()) {
                    update { copy(is4gBusy = false, fourGMessage = s(R.string.msg_4g_no_serial)) }
                    log(s(R.string.log_4g_no_serial), Tone.ERROR)
                    return@runOnIO
                }

                // Advisory only: pull a W[AM]xxx model code from anywhere in the
                // serial (a full 1581… serial won't contain one). Never blocks -
                // the dongle probe below is the real gate.
                val modelHint = Regex("[wW][aAmM][0-9]{3}").find(serial)?.value?.lowercase()
                if (modelHint != null && modelHint !in MODELS_WITH_4G) {
                    log(s(R.string.log_4g_model_note, modelHint))
                }

                // Guard 2 (authoritative): dongle pre-check. If the socket isn't
                // connectable, no cellular module is attached - stop before
                // writing 128 frames that cannot succeed.
                if (!transport.is4gDonglePresent()) {
                    update { copy(is4gBusy = false, fourGMessage = s(R.string.msg_4g_no_dongle)) }
                    log(s(R.string.log_4g_no_dongle), Tone.ERROR)
                    return@runOnIO
                }

                val profile = Profiles.load4g(app, serial)
                log(
                    s(R.string.log_4g_profile_loaded, profile.frames.size, serial, modelHint ?: s(R.string.model_unknown)),
                    Tone.BUSY
                )

                // 4G uses Unix domain socket, not TCP
                val success = transport.sendFramesUnix(
                    frames = profile.frames,
                    interFrameDelayMs = profile.interFrameDelay
                ) { progress -> update { copy(busyProgress = progress) } }

                if (success) {
                    update { copy(is4gBusy = false, busyProgress = 0f, fourGMessage = s(R.string.msg_4g_done)) }
                    log(s(R.string.log_4g_done, profile.frames.size), Tone.OK)
                } else {
                    update { copy(is4gBusy = false, fourGMessage = s(R.string.msg_4g_failed)) }
                    log(s(R.string.log_4g_failed), Tone.ERROR)
                }
            } catch (e: Exception) {
                log(s(R.string.log_4g_error, e.message.orEmpty()), Tone.ERROR)
                update { copy(is4gBusy = false, fourGMessage = s(R.string.msg_4g_error, e.message.orEmpty())) }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- LED ---

    /**
     * Turns the aircraft arm LEDs on or off.
     * Frames and port come from [AircraftProfile.ledAsset]: universal writes the
     * g_config.* LED parameter on 40007 (wrapped), Lito X1 writes forearm_led_ctrl
     * on 40008 (unwrapped) - never the standard 40009 DUML port.
     * Requires DJI Fly running with the aircraft connected.
     *
     * Sends the LED command in 2 bursts of 5 writes each (10 total), with
     * 100ms between writes - matching the reference app's pattern for
     * reliability.
     *
     * **Does NOT hold HardwareLock.** The LED command targets port 40007/40008
     * (flight controller parameter) while the FCC keepalive targets port 40009
     * (radio subsystem). They use different ports and different subsystems,
     * so they can run concurrently without conflict. Holding the lock during
     * the LED command would block the keepalive for ~1.5s, creating a gap
     * where DJI Fly could reset the radio to CE. By not holding the lock,
     * the keepalive continues re-applying FCC throughout the LED command.
     * Only the [AppState.isLedBusy] UI flag prevents double-taps.
     *
     * @param on true for LED ON, false for LED OFF
     */
    fun setLed(on: Boolean) {
        if (_state.value.isLedBusy) {
            log(s(R.string.log_led_busy), Tone.ERROR)
            return
        }
        val turning = s(if (on) R.string.led_turning_on else R.string.led_turning_off)
        update { copy(isLedBusy = true, ledStatus = turning, ledTone = Tone.BUSY) }
        log(turning, Tone.BUSY)

        runOnIO {
            try {
                val profile = Profiles.load(app, AircraftProfile.ledAsset(AircraftProfile.get(app), on))
                log(s(R.string.log_led_profile_loaded, profile.frames.size, profile.port), Tone.BUSY)

                if (writeParamProfile(profile)) {
                    update {
                        copy(
                            isLedBusy = false,
                            ledStatus = s(if (on) R.string.led_status_on else R.string.led_status_off),
                            ledTone = if (on) Tone.OK else Tone.INFO
                        )
                    }
                    log(s(if (on) R.string.log_led_on else R.string.log_led_off), Tone.OK)
                } else {
                    update { copy(isLedBusy = false, ledStatus = s(R.string.led_status_failed), ledTone = Tone.ERROR) }
                    log(s(R.string.log_led_failed), Tone.ERROR)
                }
            } catch (e: Exception) {
                log(s(R.string.log_led_error, e.message.orEmpty()), Tone.ERROR)
                update { copy(isLedBusy = false, ledStatus = s(R.string.error_fmt, e.message.orEmpty()), ledTone = Tone.ERROR) }
            }
        }
    }

    /**
     * Writes one flight-controller parameter profile (LED, altitude limit) on its
     * own transport and port: 2 connection bursts x 5 writes each (10 sends),
     * 100 ms between writes and between bursts - the reference app's reliability
     * pattern. Returns true when at least one burst was written.
     */
    private suspend fun writeParamProfile(profile: Profiles.Profile): Boolean {
        // Separate transport instance - a parameter write on port 40007/40008
        // must not share state with the FCC transport on port 40009.
        val transport = DumlTransport()
        var anySuccess = false
        for (attempt in 0 until 2) {
            if (attempt > 0) delay(100)
            val success = transport.sendFrames(
                frames = profile.frames,
                rounds = 5,
                interFrameDelayMs = 100,
                interRoundDelayMs = 0,
                readWindowMs = 100,
                port = profile.port
            )
            if (success) anySuccess = true
        }
        return anySuccess
    }

    // --- Altitude limit ---

    /**
     * Writes the flight controller's max altitude: 500 m (unlock) or 120 m.
     * Frames and port come from [AircraftProfile.altitudeAsset]; a profile without
     * a measured write has no card, so the null branch only guards the API.
     * Same transport pattern and the same no-HardwareLock reasoning as [setLed]:
     * the write goes to the FLYC inject port, not the radio port the keepalive
     * holds. The parameter persists across DJI Fly relinks (lmdegreeds dump on
     * RC 2 + Lito X1), so it is sent once and never by the keepalive.
     *
     * @param unlock true for 500 m, false for 120 m
     */
    fun setAltitudeLimit(unlock: Boolean) {
        if (_state.value.isAltitudeBusy) {
            log(s(R.string.log_altitude_busy), Tone.ERROR)
            return
        }
        val asset = AircraftProfile.altitudeAsset(AircraftProfile.get(app), unlock)
        if (asset == null) {
            log(s(R.string.log_altitude_no_profile), Tone.ERROR)
            return
        }
        val metres = if (unlock) 500 else 120
        val writing = s(R.string.altitude_writing_fmt, metres)
        update { copy(isAltitudeBusy = true, altitudeStatus = writing, altitudeTone = Tone.BUSY) }
        log(writing, Tone.BUSY)

        runOnIO {
            try {
                val profile = Profiles.load(app, asset)
                log(s(R.string.log_altitude_profile_loaded, profile.frames.size, profile.port), Tone.BUSY)

                if (writeParamProfile(profile)) {
                    update {
                        copy(
                            isAltitudeBusy = false,
                            altitudeStatus = s(R.string.altitude_status_fmt, metres),
                            altitudeTone = if (unlock) Tone.OK else Tone.INFO
                        )
                    }
                    log(s(R.string.log_altitude_set, metres), Tone.OK)
                } else {
                    update { copy(isAltitudeBusy = false, altitudeStatus = s(R.string.altitude_status_failed), altitudeTone = Tone.ERROR) }
                    log(s(R.string.log_altitude_failed), Tone.ERROR)
                }
            } catch (e: Exception) {
                log(s(R.string.log_altitude_error, e.message.orEmpty()), Tone.ERROR)
                update { copy(isAltitudeBusy = false, altitudeStatus = s(R.string.error_fmt, e.message.orEmpty()), altitudeTone = Tone.ERROR) }
            }
        }
    }

    // --- Device Info ---

    /**
     * Queries the controller for hardware version, bootloader version, and
     * firmware version via the GENERAL VersionInquiry command
     * (cmd_set=0, cmd_id=1). Uses sendAndReceive to capture the response.
     */
    fun queryDeviceInfo() {
        if (!isControllerReachable()) return
        if (!beginHardwareOp()) {
            log(s(R.string.log_hw_busy), Tone.ERROR)
            return
        }

        update { copy(isQueryingInfo = true) }
        log(s(R.string.log_querying_info), Tone.BUSY)

        runOnIO {
            try {
                val profile = Profiles.load(app, "device_info.json")
                if (profile.frames.isEmpty()) {
                    update { copy(isQueryingInfo = false, deviceInfo = s(R.string.info_profile_empty)) }
                    log(s(R.string.log_info_profile_empty), Tone.ERROR)
                    return@runOnIO
                }
                val frame = profile.frames.first()

                val response = transport.sendAndReceive(frame, profile.readWindowMs)

                if (response == null || response.isEmpty()) {
                    update { copy(isQueryingInfo = false, deviceInfo = s(R.string.info_no_response)) }
                    log(s(R.string.log_info_no_response), Tone.ERROR)
                    return@runOnIO
                }

                val info = formatVersionResponse(response)
                update { copy(isQueryingInfo = false, deviceInfo = info) }
                log(s(R.string.log_info_received, response.size), Tone.OK)
            } catch (e: Exception) {
                log(s(R.string.log_info_error, e.message.orEmpty()), Tone.ERROR)
                update { copy(isQueryingInfo = false, deviceInfo = s(R.string.error_fmt, e.message.orEmpty())) }
            } finally {
                endHardwareOp()
            }
        }
    }

    fun probeSerial() {
        if (!beginHardwareOp()) {
            log(s(R.string.log_hw_busy), Tone.ERROR)
            return
        }
        log(s(R.string.log_reading_serial), Tone.BUSY)
        update { copy(isProbingSerial = true) }
        runOnIO {
            try {
                val serial = readSerialFromAircraft()
                if (serial.isNotEmpty()) {
                    update { copy(aircraftSerial = serial) }
                    prefs.edit().putString("aircraft_serial", serial).apply()
                    log(s(R.string.log_serial_cached, serial), Tone.OK)
                } else {
                    log(s(R.string.log_serial_none), Tone.ERROR)
                }
            } finally {
                update { copy(isProbingSerial = false) }
                endHardwareOp()
            }
        }
    }

    /**
     * Asks the aircraft for its serial, most deterministic route first: the 40009
     * queries, then 00:51 on 40007 (the route that answers on Lito X1), then a long
     * passive telemetry listen. Touches DJI Fly's video port, so call it only from
     * an action the user started (Read serial, 4G activation).
     */
    private fun readSerialFromAircraft(): String {
        var serial = transport.probeSerialActive(800)
        if (serial.isEmpty()) serial = transport.querySerialOnVideoPort()
        if (serial.isEmpty()) {
            log(s(R.string.log_serial_listen), Tone.BUSY)
            serial = transport.probeSerial(8000)
        }
        return serial
    }

    // --- Log export ---

    /**
     * Saves the activity log (oldest entry first) as a text file in the public
     * Download/FreeFCC folder, so it can be read later in a file manager or over
     * USB - useful after a session in the field with no network. MediaStore
     * needs no storage permission on Android 10+ (minSdk 29). If MediaStore is
     * unavailable on a trimmed controller ROM, it falls back to the app's own
     * external files folder.
     */
    fun exportLog() {
        if (_state.value.isExportingLog) return
        val snapshot = _state.value
        update { copy(isExportingLog = true) }

        runOnIO {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val fileName = "freefcc-log-$stamp.txt"
                val text = buildString {
                    appendLine("FreeFCC ${BuildConfig.VERSION_NAME} (${UpdateChecker.REPO})")
                    appendLine("Controller: ${snapshot.controllerModel}")
                    appendLine("Aircraft S/N: ${snapshot.aircraftSerial}")
                    appendLine("Saved: $stamp")
                    appendLine()
                    snapshot.logMessages.asReversed().forEach { appendLine(it.toString()) }
                }
                val where = try {
                    saveToDownloads(fileName, text)
                } catch (_: Exception) {
                    val dir = java.io.File(app.getExternalFilesDir(null), "logs").apply { mkdirs() }
                    val file = java.io.File(dir, fileName)
                    file.writeText(text)
                    file.absolutePath
                }
                log(s(R.string.log_export_saved, where), Tone.OK)
            } catch (e: Exception) {
                log(s(R.string.log_export_failed, e.message.orEmpty()), Tone.ERROR)
            } finally {
                update { copy(isExportingLog = false) }
            }
        }
    }

    /** Writes [text] to Download/FreeFCC/[fileName] via MediaStore; returns the user-facing path. */
    private fun saveToDownloads(fileName: String, text: String): String {
        val folder = "${Environment.DIRECTORY_DOWNLOADS}/FreeFCC"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
        }
        val resolver = app.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "MediaStore insert returned null"
        }
        checkNotNull(resolver.openOutputStream(uri)) { "Cannot open $uri" }.use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
        return "$folder/$fileName"
    }

    // --- Updates ---

    fun checkForUpdates(force: Boolean = false) {
        // Rate-limit: don't hit GitHub API more than once per hour.
        // Unauthenticated limit is 60 requests/hour per IP.
        // The timestamp is saved ONLY on success - a failed check does NOT
        // consume the rate-limit window, so the user can retry immediately.
        val lastCheck = prefs.getLong("last_update_check", 0)
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 60 * 60 * 1000 && _state.value.updateChecked && _state.value.updateInfo != null) {
            return
        }
        update { copy(isCheckingUpdate = true) }
        log(s(R.string.log_checking_updates))

        runOnIO {
            when (val result = UpdateChecker.fetchLatest()) {
                UpdateCheck.Failed -> {
                    // Don't save lastCheck on failure - let the user retry immediately.
                    update { copy(isCheckingUpdate = false, updateChecked = true, updateNoRelease = false) }
                    log(s(R.string.log_update_failed), Tone.ERROR)
                }
                UpdateCheck.NoRelease -> {
                    update {
                        copy(
                            isCheckingUpdate = false,
                            updateChecked = true,
                            updateNoRelease = true,
                            updateInfo = null,
                            updateAvailable = false
                        )
                    }
                    log(s(R.string.log_update_no_release, UpdateChecker.REPO))
                }
                is UpdateCheck.Found -> {
                    // Save the timestamp only on success.
                    prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()

                    val info = result.info
                    val isNewer = info.isNewerThan(BuildConfig.VERSION_NAME)
                    update {
                        copy(
                            updateInfo = info,
                            updateNoRelease = false,
                            isCheckingUpdate = false,
                            updateChecked = true,
                            updateAvailable = isNewer
                        )
                    }
                    if (isNewer) {
                        log(s(R.string.log_update_available, info.version), Tone.OK)
                    } else {
                        log(s(R.string.log_up_to_date, BuildConfig.VERSION_NAME))
                    }
                }
            }
        }
    }

    private var downloadedApk: java.io.File? = null

    /**
     * Checks if the app can install packages. If not, opens the system
     * Settings page for "Install unknown apps" so the user can grant it.
     * Returns true if permission is already granted (proceed with download),
     * false if we opened Settings (user needs to grant, then tap Download again).
     */
    fun ensureInstallPermission(): Boolean {
        val pm = app.packageManager
        if (android.os.Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            log(s(R.string.log_install_perm_needed), Tone.ERROR)
            val settingsIntent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
            ).apply {
                data = android.net.Uri.parse("package:${app.packageName}")
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                app.startActivity(settingsIntent)
            } catch (_: Exception) {
                log(s(R.string.log_settings_unavailable_download), Tone.ERROR)
            }
            return false
        }
        return true
    }

    fun downloadUpdate() {
        val info = _state.value.updateInfo ?: return
        if (_state.value.isDownloadingUpdate) return
        // Check install permission BEFORE downloading so the user can grant
        // it first, then come back and tap Download again.
        if (!ensureInstallPermission()) {
            return
        }
        update { copy(isDownloadingUpdate = true, updateDownloadProgress = 0f, isUpdateDownloaded = false) }
        log(s(R.string.log_downloading, info.version), Tone.BUSY)

        runOnIO {
            val file = UpdateChecker.downloadApk(app, info) { progress ->
                update { copy(updateDownloadProgress = progress) }
            }

            if (file == null) {
                update { copy(isDownloadingUpdate = false, updateDownloadProgress = 0f) }
                log(s(R.string.log_download_failed), Tone.ERROR)
                return@runOnIO
            }

            downloadedApk = file
            update { copy(isDownloadingUpdate = false, updateDownloadProgress = 1f, isUpdateDownloaded = true) }
            log(s(R.string.log_downloaded), Tone.OK)
        }
    }

    /** Re-downloads the update after a failed install. Resets the downloaded state first. */
    fun reDownloadUpdate() {
        if (_state.value.isDownloadingUpdate) return
        downloadedApk = null
        update { copy(isUpdateDownloaded = false) }
        downloadUpdate()
    }

    fun installUpdate() {
        val file = downloadedApk ?: run {
            log(s(R.string.log_no_apk), Tone.ERROR)
            return
        }
        if (!file.exists()) {
            log(s(R.string.log_apk_missing), Tone.ERROR)
            downloadedApk = null
            update { copy(isUpdateDownloaded = false) }
            return
        }
        update { copy(isBusy = true, message = s(R.string.msg_preparing_install)) }
        runOnIO {
            try {
                val pm = app.packageManager

                // Check 1: does this app have permission to install packages?
                // On Android 8+ the user must grant "Install unknown apps"
                // per-app. The RC2 may hide this Settings page - if so, the
                // user needs to install via SD card + FileManager instead.
                if (android.os.Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
                    log(s(R.string.log_install_blocked), Tone.ERROR)
                    log(s(R.string.log_install_open_settings))
                    val settingsIntent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
                    ).apply {
                        data = android.net.Uri.parse("package:${app.packageName}")
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        app.startActivity(settingsIntent)
                    } catch (_: Exception) {
                        log(s(R.string.log_settings_unavailable_install), Tone.ERROR)
                    }
                    update { copy(isBusy = false, message = s(R.string.msg_grant_install)) }
                    return@runOnIO
                }

                // Copy the APK to a location the installer can access.
                // The RC2's package installer may not handle content:// URIs
                // from app-private cacheDir (a known Android issue). Copying
                // to the app-specific external directory is more reliable.
                val extDir = java.io.File(app.getExternalFilesDir(null), "updates").apply { mkdirs() }
                val extFile = java.io.File(extDir, "freefcc_update.apk")
                try {
                    file.copyTo(extFile, overwrite = true)
                } catch (e: Exception) {
                    log(s(R.string.log_copy_apk_failed, e.message.orEmpty()), Tone.ERROR)
                    // Fall back to the cache file
                }
                val installFile = if (extFile.exists()) extFile else file

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    app, "${app.packageName}.fileprovider", installFile
                )
                val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                }

                // Check 2: does a package installer actually exist?
                if (viewIntent.resolveActivity(pm) == null) {
                    log(s(R.string.log_no_installer_sideload), Tone.ERROR)
                    update { copy(isBusy = false, message = s(R.string.msg_no_installer_sideload)) }
                    return@runOnIO
                }

                // Grant URI permission to all resolved installer activities -
                // some OEM forks don't honor FLAG_GRANT_READ_URI_PERMISSION
                // alone for the staging step.
                val targets = pm.queryIntentActivities(viewIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                for (info in targets) {
                    app.grantUriPermission(
                        info.activityInfo.packageName, uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

                try {
                    app.startActivity(viewIntent)
                    log(s(R.string.log_launching_installer), Tone.OK)
                    update { copy(isBusy = false, message = s(R.string.msg_installer_launched)) }
                } catch (e: android.content.ActivityNotFoundException) {
                    log(s(R.string.log_no_installer_sd), Tone.ERROR)
                    update { copy(isBusy = false, message = s(R.string.msg_no_installer_sd)) }
                } catch (e: Exception) {
                    val text = s(R.string.install_failed, e.message.orEmpty())
                    log(text, Tone.ERROR)
                    update { copy(isBusy = false, message = text) }
                }
            } catch (e: Exception) {
                val text = s(R.string.install_error, e.message.orEmpty())
                log(text, Tone.ERROR)
                update { copy(isBusy = false, message = text) }
            }
        }
    }

    // --- Helpers ---

    /** Returns true if the controller is connected, logs a hint if not. */
    private fun isControllerReachable(): Boolean {
        if (_state.value.isConnected) return true
        log(s(R.string.log_connect_first), Tone.ERROR)
        return false
    }

    /**
     * Resolves the aircraft serial for 4G, in priority order:
     *   1. A manually-entered serial (always wins).
     *   2. The serial from this session / a previous session (cache).
     *   3. An active version/serial query to the aircraft.
     *   4. A longer passive telemetry listen.
     * The first non-empty result is cached in SharedPreferences.
     */
    private fun getOrProbeSerial(): String {
        // 1. Manual serial takes priority - trust exactly what the user typed,
        //    whatever the format (do NOT re-validate; they entered it on purpose).
        val manual = prefs.getString("manual_aircraft_sn", "").orEmpty()
        if (manual.isNotEmpty()) {
            update { copy(aircraftSerial = manual) }
            return manual
        }

        // 2. Cached from this or a previous session.
        var serial = _state.value.aircraftSerial
        if (serial.isEmpty()) serial = prefs.getString("aircraft_serial", "").orEmpty()
        if (serial.isNotEmpty()) {
            update { copy(aircraftSerial = serial) }
            return serial
        }

        // 3. Active queries, then 4. longer passive listen.
        log(s(R.string.log_reading_serial), Tone.BUSY)
        serial = readSerialFromAircraft()
        if (serial.isNotEmpty()) {
            update { copy(aircraftSerial = serial) }
            prefs.edit().putString("aircraft_serial", serial).apply()
            log(s(R.string.log_serial_cached, serial), Tone.OK)
        }
        return serial
    }

    /**
     * Parses a DUML VersionInquiry response payload into a human-readable string.
     *
     * Response layout (from dji-firmware-tools DJIPayload_General_VersionInquiryRe):
     *   byte  0-1    unknown
     *   bytes 2-17   hardware version (16-char ASCII string)
     *   bytes 18-21  bootloader version (uint32 LE)
     *   bytes 22-25  firmware version (uint32 LE)
     */
    private fun formatVersionResponse(payload: ByteArray): String {
        val lines = mutableListOf<String>()

        if (payload.size >= 18) {
            val hwVersion = String(payload, 2, 16, Charsets.US_ASCII).trimEnd('\u0000')
            lines.add(s(R.string.info_hardware, hwVersion))
        }

        if (payload.size >= 22) {
            val ldrVersion = readUInt32LE(payload, 18)
            lines.add(s(R.string.info_bootloader, formatVersion(ldrVersion)))
        }

        if (payload.size >= 26) {
            val appVersion = readUInt32LE(payload, 22)
            lines.add(s(R.string.info_firmware, formatVersion(appVersion)))
        }

        lines.add("")
        lines.add(s(R.string.info_raw_payload, payload.size))
        lines.add(payload.joinToString(" ") { "%02x".format(it) })

        return lines.joinToString("\n")
    }

    /** Reads a 32-bit little-endian unsigned integer from a byte array. */
    private fun readUInt32LE(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF)) or
               ((data[offset + 1].toLong() and 0xFF) shl 8) or
               ((data[offset + 2].toLong() and 0xFF) shl 16) or
               ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    /** Formats a DJI firmware version uint32 as major.minor.patch.build. */
    private fun formatVersion(version: Long): String {
        val major = (version shr 24) and 0xFF
        val minor = (version shr 16) and 0xFF
        val patch = (version shr 8) and 0xFF
        val build = version and 0xFF
        return "$major.$minor.$patch.$build"
    }

    /** Localized string in the in-app language. */
    private fun s(@StringRes id: Int, vararg args: Any): String = res.getString(id, *args)

    /** Atomically updates the state via a copy() block. */
    private fun update(block: AppState.() -> AppState) {
        _state.value = _state.value.block()
    }

    /** Adds a timestamped entry to the activity log (most recent first, max 50). */
    private fun log(message: String, tone: Tone = Tone.INFO) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = LogEntry(time, message, tone)
        update { copy(logMessages = (listOf(entry) + logMessages).take(50)) }
    }

    /** Launches a coroutine on Dispatchers.IO for network operations. */
    private fun runOnIO(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }
}
