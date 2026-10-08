package com.freefcc.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.math.PI

// ═══════════════════════════════════════════════════════════════════════
// Colors
// ═══════════════════════════════════════════════════════════════════════

private val BgDark = Color(0xFF070A14)
private val BgMid = Color(0xFF0D1220)
private val BgLight = Color(0xFF121830)
private val CardBg = Color(0xFF10162A)
private val CardBorder = Color(0xFF1C2848)
private val Cyan = Color(0xFF4FC3F7)
private val Green = Color(0xFF34D399)
private val Amber = Color(0xFFF59E0B)
private val Red = Color(0xFFEF4444)
private val TextWhite = Color(0xFFF0F4FF)
private val TextGray = Color(0xFF7A85A3)
private val TextDim = Color(0xFF4A5374)

private val BottomNavHeight = 72.dp

/** Colour for a log entry or status line. */
private fun Tone.color(): Color = when (this) {
    Tone.OK -> Green
    Tone.ERROR -> Red
    Tone.BUSY -> Amber
    Tone.INFO -> Cyan.copy(0.6f)
}

// ═══════════════════════════════════════════════════════════════════════
// Activity
// ═══════════════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {

    private val viewModel: FccViewModel by viewModels()

    /** Applies the in-app language choice before any string is resolved. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.init()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Cyan, onPrimary = BgDark,
                    background = BgDark, onBackground = TextWhite,
                    surface = CardBg, onSurface = TextWhite,
                    error = Red, secondary = Green, tertiary = Amber
                )
            ) {
                AppRoot(viewModel)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Root layout
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun AppRoot(viewModel: FccViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = 0) { 5 }
    val scope = rememberCoroutineScope()

    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, tween(700, easing = EaseOutCubic))
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(BgDark, BgMid, BgDark),
                    startY = 0f,
                    endY = Float.POSITIVE_INFINITY
                )
            )
            .alpha(entrance.value)
    ) {
        // Ambient glow — decorative only
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.radialGradient(
                        listOf(Cyan.copy(0.05f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = 600f
                    )
                )
        )

        // Page content — fills space above the bottom nav
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true
        ) { page ->
            when (page) {
                0 -> FccPage(state, viewModel)
                1 -> InfoPage(state, viewModel)
                2 -> LogPage(state, viewModel)
                3 -> UpdatePage(state, viewModel)
                4 -> SupportPage()
            }
        }

        // Bottom nav — fixed at the bottom, on top of everything
        BottomNavBar(
            currentPage = pagerState.currentPage,
            onPageSelected = { index ->
                scope.launch { pagerState.animateScrollToPage(index) }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Page 1: FCC
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun FccPage(state: AppState, viewModel: FccViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = BottomNavHeight + 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))
        AppHeader(state.controllerModel)
        Spacer(Modifier.height(28.dp))
        ConnectionPill(state)

        // Update-available banner — shows on the FCC page so the user
        // doesn't have to manually check the Update tab.
        if (state.updateAvailable && state.updateInfo != null && !state.isCheckingUpdate) {
            Spacer(Modifier.height(16.dp))
            GlowCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.update_banner_title, state.updateInfo!!.version),
                            color = Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.update_banner_hint),
                            color = TextDim, fontSize = 12.sp
                        )
                    }
                    Icon(
                        Icons.Filled.NewReleases,
                        contentDescription = stringResource(R.string.cd_update_available),
                        tint = Green,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        GlowCard {
            ModeBadge(state)
            Spacer(Modifier.height(20.dp))

            when {
                state.isBusy -> {
                    ProgressDisplay(state.busyProgress, state.message)
                }
                !state.isConnected -> {
                    BodyText(stringResource(R.string.fcc_connect_hint))
                    Spacer(Modifier.height(20.dp))
                    GlowButton(stringResource(R.string.btn_connect), Cyan, enabled = !state.isHardwareBusy) { viewModel.connect() }
                }
                state.isFccEnabled -> {
                    BodyText(stringResource(R.string.fcc_active), Green)
                    Spacer(Modifier.height(20.dp))
                    GlowButton(stringResource(R.string.btn_stop_fcc), Red, enabled = !state.isHardwareBusy) { viewModel.disableFcc() }
                    Spacer(Modifier.height(12.dp))
                    GlowButton(stringResource(R.string.btn_reapply_fcc), Cyan, filled = false, enabled = !state.isHardwareBusy) { viewModel.enableFcc() }
                    Spacer(Modifier.height(12.dp))
                    GlowButton(stringResource(R.string.btn_launch_fly), Green, filled = false, enabled = !state.isHardwareBusy) {
                        viewModel.launchDjiFly()
                    }
                    Spacer(Modifier.height(16.dp))
                    // Keepalive toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.keepalive_title), color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (state.isKeepaliveRunning) stringResource(R.string.keepalive_running)
                                else stringResource(R.string.keepalive_idle),
                                color = if (state.isKeepaliveRunning) Green else TextGray,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.isKeepaliveRunning,
                            onCheckedChange = { enabled ->
                                if (enabled) viewModel.startKeepalive() else viewModel.stopKeepalive()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Green,
                                checkedTrackColor = Green.copy(0.3f),
                                uncheckedThumbColor = TextGray,
                                uncheckedTrackColor = BgLight
                            )
                        )
                    }
                }
                else -> {
                    if (state.message.isNotEmpty()) {
                        BodyText(state.message)
                        Spacer(Modifier.height(20.dp))
                    } else {
                        BodyText(stringResource(R.string.fcc_enable_hint))
                        Spacer(Modifier.height(20.dp))
                    }
                    GlowButton(stringResource(R.string.btn_enable_fcc), Cyan, enabled = !state.isHardwareBusy) { viewModel.enableFcc() }
                }
            }

            if (state.aircraftSerial.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SerialRow(state.aircraftSerial, enabled = !state.isHardwareBusy) { viewModel.probeSerial() }
            }
        }

        Spacer(Modifier.height(16.dp))

        AnimatedVisibility(
            visible = state.isConnected,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            Column {
                GlowCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SignalWaveIcon(
                            active = false,
                            color = Amber,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.fourg_title),
                            color = TextWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    BodyText(
                        if (state.fourGMessage.isNotEmpty()) state.fourGMessage
                        else stringResource(R.string.fourg_desc),
                        TextGray
                    )

                    Spacer(Modifier.height(16.dp))

                    // Aircraft serial — embedded in every 4G frame. Auto-detect
                    // (active query, then telemetry) or type it in if detection fails.
                    var serialField by remember(state.aircraftSerial, state.manualSerial) {
                        mutableStateOf(state.manualSerial.ifEmpty { state.aircraftSerial })
                    }
                    OutlinedTextField(
                        value = serialField,
                        onValueChange = { serialField = it.trim() },
                        label = { Text(stringResource(R.string.serial_label)) },
                        placeholder = { Text(stringResource(R.string.serial_placeholder)) },
                        singleLine = true,
                        enabled = !state.isHardwareBusy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedBorderColor = Amber,
                            unfocusedBorderColor = TextGray,
                            focusedLabelColor = Amber,
                            unfocusedLabelColor = TextGray,
                            focusedPlaceholderColor = TextGray,
                            unfocusedPlaceholderColor = TextGray,
                            cursorColor = Amber
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    GlowButton(
                        if (state.isProbingSerial) stringResource(R.string.btn_reading_serial) else stringResource(R.string.btn_read_serial),
                        Cyan, filled = false, enabled = !state.isHardwareBusy
                    ) { viewModel.probeSerial() }
                    if (serialField.isNotBlank() && serialField != state.manualSerial) {
                        Spacer(Modifier.height(8.dp))
                        GlowButton(stringResource(R.string.btn_use_serial), Cyan, filled = false, enabled = !state.isHardwareBusy) {
                            viewModel.setManualSerial(serialField)
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    if (state.is4gBusy) {
                        ProgressDisplay(state.busyProgress, stringResource(R.string.fourg_sending))
                    } else {
                        GlowButton(stringResource(R.string.btn_send_4g), Amber, enabled = !state.isHardwareBusy) {
                            viewModel.send4gActivationFrames()
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // LED control card
        Spacer(Modifier.height(16.dp))
        GlowCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.led_title), color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.led_desc),
                        color = TextGray,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    if (state.ledStatus.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.led_status_fmt, state.ledStatus),
                            color = when (state.ledTone) {
                                Tone.OK -> Green
                                Tone.INFO -> TextGray
                                Tone.BUSY -> Amber
                                Tone.ERROR -> Red
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { viewModel.setLed(true) },
                    enabled = state.isConnected && !state.isLedBusy && !state.isHardwareBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Green,
                        contentColor = BgDark,
                        disabledContainerColor = Green.copy(0.2f),
                        disabledContentColor = Green.copy(0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Green.copy(0.3f)),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(stringResource(R.string.btn_led_on), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Button(
                    onClick = { viewModel.setLed(false) },
                    enabled = state.isConnected && !state.isLedBusy && !state.isHardwareBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = TextGray,
                        disabledContainerColor = TextGray.copy(0.1f),
                        disabledContentColor = TextGray.copy(0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, TextGray.copy(0.5f)),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(stringResource(R.string.btn_led_off), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }

        // Altitude limit card - only for a profile with a measured write (Lito X1),
        // see AircraftProfile.altitudeAsset. Same layout as the LED card above.
        if (AircraftProfile.altitudeAsset(state.aircraftProfile, unlock = true) != null) {
            Spacer(Modifier.height(16.dp))
            GlowCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.altitude_title), color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.altitude_desc),
                            color = TextGray,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                        if (state.altitudeStatus.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.altitude_status_line, state.altitudeStatus),
                                color = when (state.altitudeTone) {
                                    Tone.OK -> Green
                                    Tone.INFO -> TextGray
                                    Tone.BUSY -> Amber
                                    Tone.ERROR -> Red
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.setAltitudeLimit(true) },
                        enabled = state.isConnected && !state.isAltitudeBusy && !state.isHardwareBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Green,
                            contentColor = BgDark,
                            disabledContainerColor = Green.copy(0.2f),
                            disabledContentColor = Green.copy(0.4f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Green.copy(0.3f)),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Text(stringResource(R.string.btn_altitude_500), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Button(
                        onClick = { viewModel.setAltitudeLimit(false) },
                        enabled = state.isConnected && !state.isAltitudeBusy && !state.isHardwareBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = TextGray,
                            disabledContainerColor = TextGray.copy(0.1f),
                            disabledContentColor = TextGray.copy(0.3f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.5.dp, TextGray.copy(0.5f)),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Text(stringResource(R.string.btn_altitude_120), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }

        // Auto-FCC toggle card
        Spacer(Modifier.height(16.dp))
        GlowCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.autofcc_title), color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.autofcc_desc),
                        color = TextGray,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = state.autoFcc,
                    onCheckedChange = { viewModel.toggleAutoFcc() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Cyan,
                        checkedTrackColor = Cyan.copy(0.3f),
                        uncheckedThumbColor = TextGray,
                        uncheckedTrackColor = BgLight
                    )
                )
            }
        }
    }
}
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun InfoPage(state: AppState, viewModel: FccViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = BottomNavHeight + 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))
        PageTitle(stringResource(R.string.info_title), Icons.Outlined.Info)
        Spacer(Modifier.height(28.dp))

        GlowCard {
            Text(stringResource(R.string.info_connection), color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            InfoRow(stringResource(R.string.info_controller), state.controllerModel.ifEmpty { stringResource(R.string.unknown) })
            Spacer(Modifier.height(10.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))
            InfoRow(
                stringResource(R.string.info_status),
                if (state.isConnected) stringResource(R.string.status_connected) else stringResource(R.string.status_disconnected),
                valueColor = if (state.isConnected) Green else TextGray
            )
            Spacer(Modifier.height(10.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))
            InfoRow(stringResource(R.string.info_aircraft_sn), state.aircraftSerial.ifEmpty { stringResource(R.string.not_detected) })
        }

        Spacer(Modifier.height(16.dp))

        // Language - DJI controllers often lack the system language setting,
        // so the choice is made here and stored by the app (see Lang).
        val activity = LocalContext.current as? android.app.Activity
        GlowCard {
            Text(stringResource(R.string.lang_title), color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Lang.OPTIONS.forEach { code ->
                    val selected = state.language == code
                    val label = when (code) {
                        "en" -> stringResource(R.string.lang_en)
                        "pl" -> stringResource(R.string.lang_pl)
                        else -> stringResource(R.string.lang_system)
                    }
                    Button(
                        onClick = {
                            if (!selected) {
                                viewModel.setLanguage(code)
                                activity?.recreate()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Cyan else Color.Transparent,
                            contentColor = if (selected) BgDark else Cyan
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Cyan.copy(if (selected) 0.3f else 0.6f)),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Aircraft profile - picks the FCC and LED frames, see AircraftProfile.
        GlowCard {
            Text(stringResource(R.string.aircraft_profile_title), color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AircraftProfile.OPTIONS.forEach { code ->
                    val selected = state.aircraftProfile == code
                    Button(
                        onClick = { if (!selected) viewModel.setAircraftProfile(code) },
                        enabled = !state.isHardwareBusy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Cyan else Color.Transparent,
                            contentColor = if (selected) BgDark else Cyan
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Cyan.copy(if (selected) 0.3f else 0.6f)),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Text(stringResource(AircraftProfile.label(code)), fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            BodyText(stringResource(R.string.aircraft_profile_hint))
        }

        Spacer(Modifier.height(16.dp))

        GlowCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.info_version), color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = { viewModel.queryDeviceInfo() },
                    enabled = state.isConnected && !state.isQueryingInfo && !state.isHardwareBusy,
                    modifier = Modifier.size(40.dp)
                ) {
                    if (state.isQueryingInfo) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = Cyan,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Icon(Icons.Default.Refresh, stringResource(R.string.cd_query), tint = Cyan, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (state.deviceInfo.isNotEmpty()) {
                Text(
                    state.deviceInfo,
                    color = TextGray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (!state.isConnected) {
                BodyText(stringResource(R.string.info_connect_first), TextDim)
            } else {
                BodyText(stringResource(R.string.info_tap_refresh))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Page 3: Log
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun LogPage(state: AppState, viewModel: FccViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = BottomNavHeight + 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))
        PageTitle(stringResource(R.string.log_title), Icons.Outlined.History)
        Spacer(Modifier.height(28.dp))

        // Save the log to Download/FreeFCC - readable later without network.
        GlowButton(
            stringResource(R.string.btn_save_log),
            Cyan,
            filled = false,
            enabled = state.logMessages.isNotEmpty() && !state.isExportingLog
        ) { viewModel.exportLog() }
        Spacer(Modifier.height(16.dp))

        GlowCard {
            if (state.logMessages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BodyText(stringResource(R.string.log_empty), TextDim)
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 600.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    state.logMessages.forEachIndexed { index, entry ->
                        if (index > 0) {
                            Spacer(Modifier.height(2.dp))
                            DividerLine(alpha = 0.3f)
                            Spacer(Modifier.height(2.dp))
                        }
                        Text(
                            entry.toString(),
                            color = entry.tone.color(),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Page 4: Update
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun UpdatePage(state: AppState, viewModel: FccViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = BottomNavHeight + 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))
        PageTitle(stringResource(R.string.updates_title), Icons.Outlined.SystemUpdate)

        if (state.isCheckingUpdate) {
            Spacer(Modifier.height(28.dp))
            GlowCard {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(strokeWidth = 2.5.dp, color = Cyan, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(16.dp))
                    BodyText(stringResource(R.string.update_checking), Cyan)
                }
            }
            return@Column
        }

        val info = state.updateInfo
        if (info == null && state.updateNoRelease) {
            Spacer(Modifier.height(28.dp))
            GlowCard {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.CheckCircle, null, tint = TextDim, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(14.dp))
                    BodyText(stringResource(R.string.update_no_release), TextGray)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.update_no_release_hint, UpdateChecker.REPO, BuildConfig.VERSION_NAME),
                        color = TextDim, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))
                    GlowButton(stringResource(R.string.btn_check_again), Cyan, filled = false) {
                        viewModel.checkForUpdates(force = true)
                    }
                }
            }
            return@Column
        }

        if (info == null && state.updateChecked) {
            Spacer(Modifier.height(28.dp))
            GlowCard {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Outlined.CloudOff, null, tint = TextDim, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(14.dp))
                    BodyText(stringResource(R.string.update_check_failed), TextGray)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.update_check_failed_hint),
                        color = TextDim, fontSize = 12.sp, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(20.dp))
                    GlowButton(stringResource(R.string.btn_retry), Cyan) { viewModel.checkForUpdates(force = true) }
                }
            }
            return@Column
        }

        if (info == null) return@Column

        Spacer(Modifier.height(28.dp))

        GlowCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (state.updateAvailable) stringResource(R.string.update_available_title) else stringResource(R.string.up_to_date),
                        color = if (state.updateAvailable) Green else TextGray,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.update_current, BuildConfig.VERSION_NAME),
                        color = TextDim, fontSize = 12.sp
                    )
                }
                Icon(
                    if (state.updateAvailable) Icons.Filled.NewReleases else Icons.Filled.CheckCircle,
                    null,
                    tint = if (state.updateAvailable) Green else TextDim,
                    modifier = Modifier.size(36.dp)
                )
            }

            if (state.updateAvailable) {
                Spacer(Modifier.height(16.dp))
                DividerLine()
                Spacer(Modifier.height(16.dp))
            }

            if (state.updateAvailable) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.update_latest), color = TextGray, fontSize = 13.sp)
                    Text("v${info.version}", color = Green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.update_released), color = TextGray, fontSize = 13.sp)
                    Text(
                        info.publishedAt.split("T").firstOrNull() ?: "",
                        color = TextWhite, fontSize = 13.sp
                    )
                }
                if (info.apkSize > 0) {
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.update_size), color = TextGray, fontSize = 13.sp)
                        Text(
                            stringResource(R.string.size_mb, info.apkSize / 1048576.0),
                            color = TextWhite, fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            DividerLine()
            Spacer(Modifier.height(20.dp))

            Text(stringResource(R.string.changelog), color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (info.changelog.isNotEmpty()) {
                Text(
                    info.changelog,
                    color = TextGray,
                    fontSize = 12.sp,
                    lineHeight = 19.sp
                )
            } else {
                BodyText(stringResource(R.string.no_changelog), TextDim)
            }

            if (state.updateAvailable) {
                Spacer(Modifier.height(24.dp))
                when {
                    state.isDownloadingUpdate -> {
                        ProgressDisplay(
                            state.updateDownloadProgress,
                            stringResource(R.string.downloading_pct, (state.updateDownloadProgress * 100).toInt())
                        )
                    }
                    state.isUpdateDownloaded -> {
                        GlowButton(stringResource(R.string.btn_install_update), Green) {
                            viewModel.installUpdate()
                        }
                        Spacer(Modifier.height(12.dp))
                        GlowButton(stringResource(R.string.btn_download_again), Cyan, filled = false) {
                            viewModel.reDownloadUpdate()
                        }
                    }
                    else -> {
                        GlowButton(stringResource(R.string.btn_download), Green) {
                            viewModel.downloadUpdate()
                        }
                    }
                }
            }

            // "Check Again" button — always visible at the bottom of the
            // update card, whether up-to-date or an update is available.
            // Uses force=true to bypass the rate-limit so the user can
            // recheck immediately at any time.
            Spacer(Modifier.height(20.dp))
            DividerLine()
            Spacer(Modifier.height(16.dp))
            GlowButton(stringResource(R.string.btn_check_again), Cyan, filled = false) {
                viewModel.checkForUpdates(force = true)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Page 5: Support
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun SupportPage() {
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = BottomNavHeight + 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))
        PageTitle(stringResource(R.string.support_title), Icons.Outlined.FavoriteBorder)
        Spacer(Modifier.height(36.dp))

        // Pulsing heart with glow
        val heartPulse = rememberInfiniteTransition(label = "heart")
        val heartScale by heartPulse.animateFloat(
            1f, 1.15f,
            infiniteRepeatable(tween(900, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "heartScale"
        )
        val heartGlow by heartPulse.animateFloat(
            0.04f, 0.10f,
            infiniteRepeatable(tween(900, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "heartGlow"
        )

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp)) {
            Box(
                Modifier
                    .size(110.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(Red.copy(heartGlow), Color.Transparent),
                            radius = 120f
                        )
                    )
            )
            Icon(
                Icons.Filled.Favorite,
                null,
                tint = Red.copy(0.7f),
                modifier = Modifier.size(56.dp).scale(heartScale)
            )
        }

        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(R.string.support_free),
            color = TextWhite,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.support_coffee),
            color = TextGray,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        // Big Ko-fi button
        Button(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/freefcc")))
                } catch (_: Exception) {
                    // No browser installed (RC2 has no web browser)
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF5E5B),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
        ) {
            Icon(Icons.Filled.Coffee, null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.btn_coffee), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(Modifier.height(14.dp))

        // GitHub button
        Button(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${UpdateChecker.REPO}")))
                } catch (_: Exception) {
                    // No browser installed (RC2 has no web browser)
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = TextWhite
            ),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, CardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(Icons.Filled.Code, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.btn_source), fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }

        Spacer(Modifier.height(40.dp))

        // About card
        GlowCard {
            Text(stringResource(R.string.about_title), color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            BodyText(stringResource(R.string.about_body), TextGray)
            Spacer(Modifier.height(16.dp))
            DividerLine()
            Spacer(Modifier.height(16.dp))
            InfoRow(stringResource(R.string.about_version), BuildConfig.VERSION_NAME)
            Spacer(Modifier.height(12.dp))
            InfoRow(stringResource(R.string.about_license), "AGPL-3.0")
            Spacer(Modifier.height(12.dp))
            InfoRow(stringResource(R.string.about_protocol), "DUML")
            Spacer(Modifier.height(12.dp))
            InfoRow(stringResource(R.string.about_source), "github.com/${UpdateChecker.REPO}")
            Spacer(Modifier.height(12.dp))
            InfoRow(stringResource(R.string.about_original), "github.com/doesthings/FreeFCC")
            Spacer(Modifier.height(16.dp))
            DividerLine()
            Spacer(Modifier.height(16.dp))
            BodyText(stringResource(R.string.about_disclaimer), TextDim)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Shared components
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun AppHeader(model: String) {
    val glow = rememberInfiniteTransition(label = "hdr")
    val glowAlpha by glow.animateFloat(
        0.5f, 0.9f,
        infiniteRepeatable(tween(2800, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "hdrGlow"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(200.dp, 60.dp)
        ) {
            Box(
                Modifier
                    .size(200.dp, 60.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(Cyan.copy(glowAlpha * 0.12f), Color.Transparent),
                            radius = 140f
                        )
                    )
            )
            Text(
                "FreeFCC",
                color = Cyan.copy(alpha = glowAlpha),
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (model.isNotEmpty()) "v${BuildConfig.VERSION_NAME} · $model" else "v${BuildConfig.VERSION_NAME}",
            color = TextDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PageTitle(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Cyan, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, color = TextWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ConnectionPill(state: AppState) {
    val (label, color) = when {
        state.status == "connecting" -> stringResource(R.string.conn_connecting) to Amber
        state.isConnected -> stringResource(R.string.status_connected) to Green
        state.status == "error" -> stringResource(R.string.conn_error) to Red
        else -> stringResource(R.string.status_disconnected) to TextGray
    }

    // Bounce-in on state change (no scale overflow — use alpha + small bump)
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(state.isConnected) {
        if (state.isConnected) {
            bounce.snapTo(0.8f)
            bounce.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    // Pulsing glow when connected
    val glowAlpha: Float = if (state.isConnected) {
        val t = rememberInfiniteTransition(label = "pill")
        val a by t.animateFloat(0.1f, 0.25f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "pillGlow")
        a
    } else 0f

    Surface(
        color = color.copy(0.1f),
        shape = CircleShape,
        border = BorderStroke(1.dp, color.copy(0.3f)),
        modifier = Modifier
            .padding(4.dp)
            .scale(bounce.value)
            .drawBehind {
                if (glowAlpha > 0f) {
                    drawCircle(color.copy(glowAlpha), radius = size.maxDimension * 0.75f)
                }
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ModeBadge(state: AppState) {
    val active = state.isFccEnabled
    val bgBrush = if (active) {
        Brush.horizontalGradient(listOf(Color(0xFF0A2540), Color(0xFF0E3050), Color(0xFF0A2540)))
    } else {
        Brush.horizontalGradient(listOf(BgLight.copy(0.4f), BgLight.copy(0.2f)))
    }

    val checkScale = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            checkScale.snapTo(0f)
            checkScale.animateTo(1.2f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            checkScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        } else {
            checkScale.snapTo(0f)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgBrush)
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.mode_label),
                color = TextDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (active) "FCC" else "CE",
                color = if (active) Green else TextWhite,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (active) stringResource(R.string.mode_fcc_desc) else stringResource(R.string.mode_ce_desc),
                color = if (active) Green.copy(0.7f) else TextGray,
                fontSize = 12.sp
            )
        }
        if (active) {
            Icon(
                Icons.Filled.CheckCircle, null, tint = Green,
                modifier = Modifier.size(44.dp).scale(checkScale.value)
            )
        } else {
            Icon(
                Icons.Outlined.Radio, null, tint = TextDim,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
private fun ProgressDisplay(progress: Float, label: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(BgLight)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.horizontalGradient(listOf(Cyan, Green)))
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "${(progress * 100).toInt()}%",
            color = TextGray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun BodyText(text: String, color: Color = TextGray) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        lineHeight = 20.sp
    )
}

@Composable
private fun SerialRow(serial: String, enabled: Boolean = true, onRefresh: () -> Unit) {
    Surface(
        color = BgLight.copy(0.4f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(Icons.Filled.Flight, null, tint = Cyan.copy(0.6f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.serial_prefix), color = TextGray, fontSize = 12.sp)
            Text(serial, color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = enabled, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Refresh, stringResource(R.string.cd_refresh), tint = TextGray, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = TextWhite) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextGray, fontSize = 13.sp)
        Text(
            value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun DividerLine(alpha: Float = 0.5f) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CardBorder.copy(alpha))
    )
}

@Composable
private fun StatusDot(color: Color) {
    val pulse = rememberInfiniteTransition(label = "dot")
    val alpha by pulse.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "dotPulse")
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color.copy(alpha), CircleShape)
    )
}

@Composable
private fun GlowCard(content: @Composable () -> Unit) {
    Surface(
        color = CardBg,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth()
        ) {
            content()
        }
    }
}

@Composable
private fun GlowButton(
    text: String,
    color: Color,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) color else Color.Transparent,
            contentColor = if (filled) BgDark else color,
            disabledContainerColor = color.copy(0.2f),
            disabledContentColor = color.copy(0.4f)
        ),
        shape = RoundedCornerShape(12.dp),
        border = when {
            !filled && enabled -> BorderStroke(1.5.dp, color.copy(0.6f))
            filled && enabled -> BorderStroke(1.dp, color.copy(0.3f))
            else -> null
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.5.sp)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Signal wave icon
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun SignalWaveIcon(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "wavePhase"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val centerY = h / 2
        val amplitude = if (active) h * 0.25f else h * 0.08f
        val lineColor = if (active) color else color.copy(0.35f)

        val path = androidx.compose.ui.graphics.Path()
        for (x in 0..w.toInt() step 2) {
            val y = centerY + amplitude * sin((x / w).toDouble() * 2.0 * PI + phase.toDouble()).toFloat()
            if (x == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Bottom navigation bar
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun BottomNavBar(
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf(
        Triple(stringResource(R.string.tab_fcc), Icons.Filled.Wifi, Cyan),
        Triple(stringResource(R.string.tab_info), Icons.Filled.Info, Green),
        Triple(stringResource(R.string.tab_log), Icons.Filled.History, Amber),
        Triple(stringResource(R.string.tab_update), Icons.Filled.SystemUpdate, Color(0xFFB39DDB)),
        Triple(stringResource(R.string.tab_support), Icons.Filled.Favorite, Red)
    )

    Surface(
        color = BgDark.copy(0.98f),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BottomNavHeight)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, (label, icon, color) ->
                val selected = currentPage == index

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onPageSelected(index) }
                        .padding(vertical = 8.dp)
                ) {
                    Icon(
                        icon, label,
                        tint = if (selected) color else TextDim,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label,
                        color = if (selected) color else TextDim,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (selected) color else Color.Transparent)
                    )
                }
            }
        }
    }
}