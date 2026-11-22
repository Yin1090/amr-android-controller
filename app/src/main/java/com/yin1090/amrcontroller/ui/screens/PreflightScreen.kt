package com.yin1090.amrcontroller.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yin1090.amrcontroller.ui.state.PreflightState
import com.yin1090.amrcontroller.ui.theme.TacticalAmber
import com.yin1090.amrcontroller.ui.theme.TacticalBg
import com.yin1090.amrcontroller.ui.theme.TacticalBlue
import com.yin1090.amrcontroller.ui.theme.TacticalBorder
import com.yin1090.amrcontroller.ui.theme.TacticalCard
import com.yin1090.amrcontroller.ui.theme.TacticalCyan
import com.yin1090.amrcontroller.ui.theme.TacticalEmerald
import com.yin1090.amrcontroller.ui.theme.TacticalRedBright
import com.yin1090.amrcontroller.ui.theme.TacticalTextMuted
import com.yin1090.amrcontroller.ui.theme.TacticalTextPrimary
import com.yin1090.amrcontroller.ui.theme.TacticalTile

@Composable
fun PreflightScreen(
    state: PreflightState,
    demoMode: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onDemoCockpit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TacticalBg)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = TacticalCard),
            border = BorderStroke(1.dp, TacticalBorder)
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (demoMode) "DEMO PREFLIGHT" else "PREFLIGHT DIAGNOSTICS",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = "${state.readinessCount}/3",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = TacticalCyan
                    )
                }

                LinearProgressIndicator(
                    progress = { (state.tick.coerceIn(0, 3)) / 3f },
                    color = if (state.failed) TacticalRedBright else TacticalBlue,
                    trackColor = TacticalTile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                )

                if (!state.warningBanner.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TacticalAmber.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(text = state.warningBanner, color = TacticalAmber, fontSize = 12.sp)
                    }
                }

                ChecklistRow(label = "Control API", ok = state.apiOk, pending = !state.failed && state.tick < 3)
                ChecklistRow(label = "LiDAR & Map", ok = state.lidarOk, pending = !state.failed && state.tick < 3)
                ChecklistRow(label = "Camera Feed", ok = state.cameraOk, pending = !state.failed && state.tick < 3)

                Text(
                    text = state.statusDetail,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (state.failed) TacticalRedBright else TacticalTextMuted
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        border = BorderStroke(1.dp, TacticalBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("BACK", color = TacticalTextPrimary)
                    }
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = TacticalBlue),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("RETRY")
                    }
                }

                if (!demoMode) {
                    OutlinedButton(
                        onClick = onDemoCockpit,
                        border = BorderStroke(1.dp, TacticalCyan.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("DEMO COCKPIT", color = TacticalCyan, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistRow(label: String, ok: Boolean, pending: Boolean) {
    val statusText = when {
        ok -> "READY"
        pending -> "PROBING"
        else -> "OFFLINE"
    }
    val statusColor = when {
        ok -> TacticalEmerald
        pending -> TacticalAmber
        else -> TacticalRedBright
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TacticalTile, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TacticalTextPrimary, fontWeight = FontWeight.Medium)
        Text(
            text = statusText,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = statusColor
        )
    }
}
