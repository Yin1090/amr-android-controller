package com.yin1090.amrcontroller.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yin1090.amrcontroller.data.MjpegStreamClient
import com.yin1090.amrcontroller.data.MjpegStreamEvent
import com.yin1090.amrcontroller.model.ConnectionConfig
import com.yin1090.amrcontroller.net.AmrApiClient
import com.yin1090.amrcontroller.ui.theme.TacticalAmber
import com.yin1090.amrcontroller.ui.theme.TacticalBg
import com.yin1090.amrcontroller.ui.theme.TacticalBorder
import com.yin1090.amrcontroller.ui.theme.TacticalCard
import com.yin1090.amrcontroller.ui.theme.TacticalCyan
import com.yin1090.amrcontroller.ui.theme.TacticalEmerald
import com.yin1090.amrcontroller.ui.theme.TacticalTextMuted
import com.yin1090.amrcontroller.ui.theme.TacticalTextPrimary
import com.yin1090.amrcontroller.ui.theme.TacticalTile

@Composable
fun MjpegHudView(
    config: ConnectionConfig,
    apiClient: AmrApiClient,
    streamClient: MjpegStreamClient,
    demoMode: Boolean,
    modifier: Modifier = Modifier
) {
    var reloadKey by remember { mutableIntStateOf(0) }
    var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var statusText by remember { mutableStateOf(if (demoMode) "DEMO" else "STANDBY") }
    var detailText by remember { mutableStateOf(if (demoMode) "Synthetic tactical feed" else "Connecting stream...") }

    if (!demoMode) {
        LaunchedEffect(config.host, config.camPort, reloadKey) {
            currentFrame = null
            streamClient.streamFrames {
                apiClient.buildCameraStreamUrl(config, System.currentTimeMillis())
            }.collect { event ->
                when (event) {
                    is MjpegStreamEvent.Connecting -> {
                        statusText = if (currentFrame == null) "STANDBY" else "LIVE"
                    }
                    is MjpegStreamEvent.Frame -> {
                        currentFrame = event.bitmap
                        statusText = "LIVE"
                        detailText = "MJPEG 640x480"
                    }
                    is MjpegStreamEvent.Disconnected -> {
                        statusText = "STANDBY"
                        detailText = "${event.reason} • Retry in ${event.retryInMs}ms"
                    }
                }
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "hud_scanline")
    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanline_y"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(TacticalCard)
            .border(1.dp, TacticalBorder, RoundedCornerShape(10.dp))
    ) {
        val frame = currentFrame
        if (frame != null && !demoMode) {
            Image(
                bitmap = frame,
                contentDescription = "Robot MJPEG camera feed",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Standby or Demo tactical HUD backdrop with animated scanline
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(color = TacticalBg)
                val gridStep = 36f
                var x = gridStep
                while (x < size.width) {
                    drawLine(
                        color = TacticalBorder.copy(alpha = 0.45f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f
                    )
                    x += gridStep
                }
                var y = gridStep
                while (y < size.height) {
                    drawLine(
                        color = TacticalBorder.copy(alpha = 0.45f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                    y += gridStep
                }
                val scanY = scanProgress * size.height
                drawLine(
                    color = TacticalCyan.copy(alpha = 0.55f),
                    start = Offset(0f, scanY),
                    end = Offset(size.width, scanY),
                    strokeWidth = 2.5f
                )
                drawRect(
                    color = TacticalCyan.copy(alpha = 0.25f),
                    topLeft = Offset(size.width * 0.25f, size.height * 0.28f),
                    size = Size(size.width * 0.5f, size.height * 0.44f),
                    style = Stroke(width = 1.5f)
                )
            }
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (demoMode) "SIMULATED OPTICAL HUD" else "CAMERA STANDBY",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = TacticalCyan
                )
                Text(
                    text = detailText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TacticalTextMuted
                )
            }
        }

        // 4 corner reticles
        Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            val len = 16f
            val stroke = 2.2f
            val c = TacticalCyan.copy(alpha = 0.75f)
            // Top-left
            drawLine(c, Offset(0f, 0f), Offset(len, 0f), stroke)
            drawLine(c, Offset(0f, 0f), Offset(0f, len), stroke)
            // Top-right
            drawLine(c, Offset(size.width - len, 0f), Offset(size.width, 0f), stroke)
            drawLine(c, Offset(size.width, 0f), Offset(size.width, len), stroke)
            // Bottom-left
            drawLine(c, Offset(0f, size.height), Offset(len, size.height), stroke)
            drawLine(c, Offset(0f, size.height - len), Offset(0f, size.height), stroke)
            // Bottom-right
            drawLine(c, Offset(size.width - len, size.height), Offset(size.width, size.height), stroke)
            drawLine(c, Offset(size.width, size.height - len), Offset(size.width, size.height), stroke)
        }

        // Top HUD bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val badgeColor = when (statusText) {
                    "LIVE" -> TacticalEmerald
                    "DEMO" -> TacticalCyan
                    else -> TacticalAmber
                }
                Box(
                    modifier = Modifier
                        .background(TacticalBg.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = badgeColor
                    )
                }
                Text(
                    text = "640x480",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TacticalTextPrimary
                )
            }

            if (!demoMode) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(TacticalTile.copy(alpha = 0.9f))
                        .border(1.dp, TacticalBorder, RoundedCornerShape(6.dp))
                        .clickable { reloadKey += 1 }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "↻ RELOAD",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = TacticalTextPrimary
                    )
                }
            }
        }
    }
}
