package com.yin1090.amrcontroller.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yin1090.amrcontroller.data.MjpegStreamClient
import com.yin1090.amrcontroller.model.ArmState
import com.yin1090.amrcontroller.model.CockpitUiState
import com.yin1090.amrcontroller.model.ConnectionStatus
import com.yin1090.amrcontroller.model.DriveDirection
import com.yin1090.amrcontroller.model.PidGains
import com.yin1090.amrcontroller.net.AmrApiClient
import com.yin1090.amrcontroller.ui.components.ControlRightColumn
import com.yin1090.amrcontroller.ui.components.HoldDriveDPad
import com.yin1090.amrcontroller.ui.components.LidarMapCanvas
import com.yin1090.amrcontroller.ui.components.MissionOverlay
import com.yin1090.amrcontroller.ui.components.MjpegHudView
import com.yin1090.amrcontroller.ui.components.SettingsOverlay
import com.yin1090.amrcontroller.ui.components.TelemetryGrid
import com.yin1090.amrcontroller.ui.theme.TacticalAmber
import com.yin1090.amrcontroller.ui.theme.TacticalBg
import com.yin1090.amrcontroller.ui.theme.TacticalBlue
import com.yin1090.amrcontroller.ui.theme.TacticalBorder
import com.yin1090.amrcontroller.ui.theme.TacticalCard
import com.yin1090.amrcontroller.ui.theme.TacticalCyan
import com.yin1090.amrcontroller.ui.theme.TacticalEmerald
import com.yin1090.amrcontroller.ui.theme.TacticalRed
import com.yin1090.amrcontroller.ui.theme.TacticalRedBright
import com.yin1090.amrcontroller.ui.theme.TacticalTextMuted
import com.yin1090.amrcontroller.ui.theme.TacticalTextPrimary
import com.yin1090.amrcontroller.ui.theme.TacticalTile

@Composable
fun CockpitScreen(
    state: CockpitUiState,
    apiClient: AmrApiClient,
    streamClient: MjpegStreamClient,
    onDirectionPress: (DriveDirection) -> Unit,
    onDirectionRelease: () -> Unit,
    onArmClick: () -> Unit,
    onDisarmClick: () -> Unit,
    onClearFaultClick: () -> Unit,
    onStopClick: () -> Unit,
    onAdjustRpm: (Int) -> Unit,
    onOpenMission: () -> Unit,
    onCloseMission: () -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onApplyAllSettings: (host: String, apiPort: Int, camPort: Int, rpmMax: Int, pid: PidGains) -> Unit,
    onApplyPidOnly: (PidGains) -> Unit,
    onResetMap: () -> Unit,
    onToggleDemoObstacle: (DriveDirection?) -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TacticalBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 48.dp Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(TacticalCard)
                    .border(1.dp, TacticalBorder)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AMR COCKPIT",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        color = TacticalTextPrimary
                    )

                    val apiLabel = when (state.connectionStatus) {
                        ConnectionStatus.CONNECTED -> "CONNECTED"
                        ConnectionStatus.STALE -> "DATA LOST"
                        ConnectionStatus.DEMO -> "DEMO"
                        ConnectionStatus.DISCONNECTED -> "OFFLINE"
                        else -> "WAIT API"
                    }
                    val apiColor = when (state.connectionStatus) {
                        ConnectionStatus.CONNECTED -> TacticalEmerald
                        ConnectionStatus.DEMO -> TacticalCyan
                        ConnectionStatus.STALE, ConnectionStatus.DISCONNECTED -> TacticalRedBright
                        else -> TacticalAmber
                    }
                    HeaderBadge(text = apiLabel, color = apiColor)

                    val armLabel = when {
                        state.demoMode && state.armState == ArmState.ARMED -> "DEMO ARMED"
                        else -> state.armState.name
                    }
                    val armColor = when (state.armState) {
                        ArmState.ARMED -> TacticalEmerald
                        ArmState.FAULT -> TacticalRedBright
                        ArmState.DISARMED -> TacticalAmber
                    }
                    HeaderBadge(text = armLabel, color = armColor)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (state.armState) {
                        ArmState.DISARMED -> {
                            CompactActionButton(
                                text = "ARM",
                                containerColor = TacticalBlue,
                                onClick = onArmClick
                            )
                        }
                        ArmState.ARMED -> {
                            CompactActionButton(
                                text = "DISARM",
                                containerColor = TacticalAmber,
                                onClick = onDisarmClick
                            )
                        }
                        ArmState.FAULT -> {
                            CompactActionButton(
                                text = "CLEAR FAULT",
                                containerColor = TacticalRed,
                                onClick = onClearFaultClick
                            )
                        }
                    }

                    CompactOutlinedButton(text = "MISSION", onClick = onOpenMission)
                    CompactOutlinedButton(text = "SETTINGS", onClick = onOpenSettings)
                    CompactOutlinedButton(text = "EXIT", onClick = onExit)
                }
            }

            // 3-Column Landscape Body (0.36 / 0.38 / 0.26)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Left Column (0.36f)
                Column(
                    modifier = Modifier
                        .weight(0.36f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LidarMapCanvas(
                        map = state.latestMap,
                        telemetry = state.latestTelemetry,
                        mapStale = state.mapStale,
                        teleStale = state.telemetryAgeBadgeStale,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.52f)
                    )

                    HoldDriveDPad(
                        activeDirection = state.activeDirection,
                        blockedDirections = state.blockedDirections,
                        canDrive = !state.dpadLocked,
                        interlockWarning = state.interlockWarning,
                        onPress = onDirectionPress,
                        onRelease = onDirectionRelease,
                        onStopPress = onStopClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.48f)
                    )
                }

                // Center Column (0.38f)
                Column(
                    modifier = Modifier
                        .weight(0.38f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MjpegHudView(
                        config = state.config,
                        apiClient = apiClient,
                        streamClient = streamClient,
                        demoMode = state.demoMode,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.64f)
                    )

                    TelemetryGrid(
                        telemetry = state.latestTelemetry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.36f)
                    )
                }

                // Right Column (0.26f)
                ControlRightColumn(
                    state = state,
                    onAdjustRpm = onAdjustRpm,
                    onEmergencyStop = onStopClick,
                    onToggleDemoFrontObstacle = onToggleDemoObstacle,
                    modifier = Modifier
                        .weight(0.26f)
                        .fillMaxHeight()
                )
            }
        }

        if (state.missionOpen && !state.demoMode) {
            MissionOverlay(
                config = state.config,
                missionUrl = apiClient.buildMissionUrl(state.config),
                onClose = onCloseMission
            )
        }

        if (state.settingsOpen) {
            SettingsOverlay(
                state = state,
                onApplyAll = onApplyAllSettings,
                onApplyPidOnly = onApplyPidOnly,
                onResetMap = onResetMap,
                onClose = onCloseSettings
            )
        }
    }
}

@Composable
private fun HeaderBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = color
        )
    }
}

@Composable
private fun CompactActionButton(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = Modifier.height(30.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = TacticalTextPrimary
        )
    }
}

@Composable
private fun CompactOutlinedButton(
    text: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, TacticalBorder),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = Modifier.height(30.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            color = TacticalTextMuted
        )
    }
}
