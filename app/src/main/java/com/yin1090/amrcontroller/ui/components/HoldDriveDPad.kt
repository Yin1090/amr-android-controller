package com.yin1090.amrcontroller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yin1090.amrcontroller.model.DriveDirection
import com.yin1090.amrcontroller.ui.theme.TacticalAmber
import com.yin1090.amrcontroller.ui.theme.TacticalBlue
import com.yin1090.amrcontroller.ui.theme.TacticalBorder
import com.yin1090.amrcontroller.ui.theme.TacticalCard
import com.yin1090.amrcontroller.ui.theme.TacticalRed
import com.yin1090.amrcontroller.ui.theme.TacticalRedBright
import com.yin1090.amrcontroller.ui.theme.TacticalTextMuted
import com.yin1090.amrcontroller.ui.theme.TacticalTextPrimary
import com.yin1090.amrcontroller.ui.theme.TacticalTile

@Composable
fun HoldDriveDPad(
    activeDirection: DriveDirection?,
    blockedDirections: Set<DriveDirection>,
    canDrive: Boolean,
    interlockWarning: String?,
    onPress: (DriveDirection) -> Unit,
    onRelease: () -> Unit,
    onStopPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(TacticalCard)
            .border(1.dp, TacticalBorder, RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val rows = listOf(
            listOf(DriveDirection.FL, DriveDirection.F, DriveDirection.FR),
            listOf(DriveDirection.L, null, DriveDirection.R),
            listOf(DriveDirection.BL, DriveDirection.B, DriveDirection.BR)
        )

        rows.forEach { rowDirs ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowDirs.forEach { dir ->
                    if (dir == null) {
                        StopCenterButton(
                            onStopPress = onStopPress,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    } else {
                        val isBlocked = blockedDirections.contains(dir)
                        val isActive = activeDirection == dir
                        DirectionHoldButton(
                            direction = dir,
                            isActive = isActive,
                            isBlocked = isBlocked,
                            canDrive = canDrive,
                            onPress = onPress,
                            onRelease = onRelease,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }

        val hintText = interlockWarning ?: when {
            !canDrive -> "DISARMED / LOCKED — Arm robot with healthy LiDAR to drive"
            blockedDirections.isNotEmpty() -> "8-ZONE INTERLOCK ACTIVE (< 40 cm): ${blockedDirections.joinToString { it.name }}"
            else -> "HOLD-TO-DRIVE • Release immediately sends V 0.000 0.000"
        }
        val hintColor = when {
            interlockWarning != null || blockedDirections.isNotEmpty() -> TacticalAmber
            !canDrive -> TacticalTextMuted
            else -> TacticalTextMuted
        }
        Text(
            text = hintText,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = hintColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

@Composable
private fun DirectionHoldButton(
    direction: DriveDirection,
    isActive: Boolean,
    isBlocked: Boolean,
    canDrive: Boolean,
    onPress: (DriveDirection) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        isActive -> TacticalBlue
        isBlocked -> TacticalRedBright.copy(alpha = 0.15f)
        else -> TacticalTile
    }
    val borderColor = when {
        isActive -> TacticalBlue
        isBlocked -> TacticalRedBright
        else -> TacticalBorder
    }
    val label = when (direction) {
        DriveDirection.F -> "F ▲"
        DriveDirection.B -> "B ▼"
        DriveDirection.L -> "L ◀"
        DriveDirection.R -> "R ▶"
        DriveDirection.FL -> "FL ↖"
        DriveDirection.FR -> "FR ↗"
        DriveDirection.BL -> "BL ↙"
        DriveDirection.BR -> "BR ↘"
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .pointerInput(direction, canDrive) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onPress(direction)
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        onRelease()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = when {
                isBlocked -> TacticalRedBright
                canDrive -> TacticalTextPrimary
                else -> TacticalTextMuted
            }
        )
    }
}

@Composable
private fun StopCenterButton(
    onStopPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalRed)
            .border(1.dp, TacticalRedBright, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onStopPress()
                    waitForUpOrCancellation()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "STOP",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 13.sp,
            color = TacticalTextPrimary
        )
    }
}
