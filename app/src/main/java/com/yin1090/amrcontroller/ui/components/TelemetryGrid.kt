package com.yin1090.amrcontroller.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yin1090.amrcontroller.model.TelemetrySnapshot
import com.yin1090.amrcontroller.ui.theme.TacticalAmber
import com.yin1090.amrcontroller.ui.theme.TacticalBorder
import com.yin1090.amrcontroller.ui.theme.TacticalCard
import com.yin1090.amrcontroller.ui.theme.TacticalCyan
import com.yin1090.amrcontroller.ui.theme.TacticalEmerald
import com.yin1090.amrcontroller.ui.theme.TacticalRedBright
import com.yin1090.amrcontroller.ui.theme.TacticalTextMuted
import com.yin1090.amrcontroller.ui.theme.TacticalTextPrimary
import com.yin1090.amrcontroller.ui.theme.TacticalTile
import java.util.Locale
import kotlin.math.abs

@Composable
fun TelemetryGrid(
    telemetry: TelemetrySnapshot?,
    modifier: Modifier = Modifier
) {
    val leftRpm = telemetry?.robot?.leftRpm?.let {
        val clean = if (!it.isFinite() || abs(it) < 0.05) 0.0 else it
        String.format(Locale.US, "%.1f", clean)
    } ?: "--"
    val rightRpm = telemetry?.robot?.rightRpm?.let {
        val clean = if (!it.isFinite() || abs(it) < 0.05) 0.0 else it
        String.format(Locale.US, "%.1f", clean)
    } ?: "--"
    val minM = telemetry?.minDistanceM?.let {
        val clean = if (!it.isFinite() || it < 0.0 || abs(it) < 1e-6) 0.0 else it
        String.format(Locale.US, "%.2f m", clean)
    } ?: "--"
    val statusStr = telemetry?.status ?: "WAIT"

    val lidarColor = when {
        telemetry == null -> TacticalTextMuted
        telemetry.minDistanceM < 0.40 -> TacticalRedBright
        telemetry.minDistanceM < 0.70 -> TacticalAmber
        else -> TacticalEmerald
    }
    val statusColor = when (statusStr) {
        "DANGER_STOP" -> TacticalRedBright
        "WARNING" -> TacticalAmber
        "SAFE" -> TacticalEmerald
        else -> TacticalCyan
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(TacticalCard)
            .border(1.dp, TacticalBorder, RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TelemetryTile(
                label = "LEFT RPM",
                value = leftRpm,
                valueColor = TacticalTextPrimary,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            TelemetryTile(
                label = "RIGHT RPM",
                value = rightRpm,
                valueColor = TacticalTextPrimary,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TelemetryTile(
                label = "LIDAR MIN",
                value = minM,
                valueColor = lidarColor,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            TelemetryTile(
                label = "STATUS",
                value = statusStr,
                valueColor = statusColor,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun TelemetryTile(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(TacticalTile)
            .border(1.dp, TacticalBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = TacticalTextMuted
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = valueColor,
            maxLines = 1
        )
    }
}
