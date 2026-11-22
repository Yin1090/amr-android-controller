package com.yin1090.amrcontroller.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yin1090.amrcontroller.domain.Kinematics
import com.yin1090.amrcontroller.model.ArmState
import com.yin1090.amrcontroller.model.CockpitUiState
import com.yin1090.amrcontroller.model.DriveDirection
import com.yin1090.amrcontroller.ui.theme.TacticalAmber
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
import java.util.Locale

@Composable
fun ControlRightColumn(
    state: CockpitUiState,
    onAdjustRpm: (Int) -> Unit,
    onEmergencyStop: () -> Unit,
    onToggleDemoFrontObstacle: (DriveDirection?) -> Unit,
    modifier: Modifier = Modifier
) {
    val linearV = Kinematics.linearSpeedMps(state.desiredRpm)
    val angularW = Kinematics.angularSpeedRadPerSec(state.desiredRpm)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Mode & Arm Notice Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(TacticalCard)
                .border(1.dp, TacticalBorder, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "MODE & INTERLOCK",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TacticalTextMuted
            )
            val armColor = when (state.armState) {
                ArmState.ARMED -> TacticalEmerald
                ArmState.FAULT -> TacticalRedBright
                ArmState.DISARMED -> TacticalAmber
            }
            Text(
                text = if (state.demoMode) "DEMO • ${state.armState.name}" else "LIVE • ${state.armState.name}",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = armColor
            )
            Text(
                text = state.lastCommandNotice ?: state.statusMessage ?: "Ready",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TacticalTextMuted,
                maxLines = 2
            )
        }

        // Target Speed Stepper Card (40-135 RPM, +-10)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(TacticalCard)
                .border(1.dp, TacticalBorder, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TARGET SPEED",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TacticalTextMuted
                )
                Text(
                    text = "40–135 RPM",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TacticalCyan
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onAdjustRpm(-Kinematics.RPM_STEP) },
                    enabled = state.desiredRpm > Kinematics.MIN_RPM,
                    border = BorderStroke(1.dp, TacticalBorder),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("-10", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = TacticalTextPrimary)
                }

                Text(
                    text = "${state.desiredRpm} RPM",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    color = TacticalTextPrimary
                )

                OutlinedButton(
                    onClick = { onAdjustRpm(+Kinematics.RPM_STEP) },
                    enabled = state.desiredRpm < Kinematics.MAX_RPM,
                    border = BorderStroke(1.dp, TacticalBorder),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("+10", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = TacticalTextPrimary)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TacticalTile, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = String.format(Locale.US, "v=%.3f m/s", linearV),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TacticalTextPrimary
                )
                Text(
                    text = String.format(Locale.US, "ω=%.2f rad/s", angularW),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TacticalCyan
                )
            }
        }

        if (state.demoMode) {
            val frontBlocked = state.blockedDirections.contains(DriveDirection.F)
            OutlinedButton(
                onClick = {
                    if (frontBlocked) onToggleDemoFrontObstacle(null) else onToggleDemoFrontObstacle(DriveDirection.F)
                },
                border = BorderStroke(1.dp, if (frontBlocked) TacticalAmber else TacticalBorder),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (frontBlocked) "CLEAR DEMO OBSTACLE" else "INJECT <40cm FRONT OBS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (frontBlocked) TacticalAmber else TacticalCyan
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onEmergencyStop,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TacticalRed),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                text = "EMERGENCY STOP",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp,
                color = TacticalTextPrimary
            )
        }
    }
}
