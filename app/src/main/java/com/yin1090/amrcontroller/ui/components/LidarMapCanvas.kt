package com.yin1090.amrcontroller.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yin1090.amrcontroller.model.MapPose
import com.yin1090.amrcontroller.model.OccupancyMap
import com.yin1090.amrcontroller.model.TelemetrySnapshot
import com.yin1090.amrcontroller.ui.theme.TacticalAmber
import com.yin1090.amrcontroller.ui.theme.TacticalBg
import com.yin1090.amrcontroller.ui.theme.TacticalBorder
import com.yin1090.amrcontroller.ui.theme.TacticalCard
import com.yin1090.amrcontroller.ui.theme.TacticalCyan
import com.yin1090.amrcontroller.ui.theme.TacticalEmerald
import com.yin1090.amrcontroller.ui.theme.TacticalRedBright
import com.yin1090.amrcontroller.ui.theme.TacticalTextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun LidarMapCanvas(
    map: OccupancyMap?,
    telemetry: TelemetrySnapshot?,
    mapStale: Boolean,
    teleStale: Boolean,
    modifier: Modifier = Modifier
) {
    var mapBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(map) {
        if (map != null && map.isValid()) {
            mapBitmap = withContext(Dispatchers.Default) {
                rasterizeOccupancyGrid(map)
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(TacticalCard)
            .border(1.dp, TacticalBorder, RoundedCornerShape(10.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val maxRadiusPx = minOf(w, h) * 0.44f
            val rangeMaxMeters = 2.0f

            // Render occupancy grid centered on robot if available
            val bmp = mapBitmap
            if (bmp != null && map != null && map.isValid()) {
                val pxPerMeter = maxRadiusPx / rangeMaxMeters
                val drawW = (map.width * map.resolutionM * pxPerMeter).roundToInt().coerceAtLeast(1)
                val drawH = (map.height * map.resolutionM * pxPerMeter).roundToInt().coerceAtLeast(1)
                val left = (cx - drawW / 2f).roundToInt()
                val top = (cy - drawH / 2f).roundToInt()
                drawImage(
                    image = bmp,
                    dstOffset = IntOffset(left, top),
                    dstSize = IntSize(drawW, drawH),
                    alpha = 0.85f
                )
            }

            // Range rings at 0.5, 1.0, 1.5, 2.0 m
            val ringRadii = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f)
            for (rMeters in ringRadii) {
                val rPx = (rMeters / rangeMaxMeters) * maxRadiusPx
                drawCircle(
                    color = TacticalBorder,
                    radius = rPx,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.2f)
                )
            }

            // Crosshairs
            drawLine(
                color = TacticalBorder.copy(alpha = 0.8f),
                start = Offset(cx - maxRadiusPx, cy),
                end = Offset(cx + maxRadiusPx, cy),
                strokeWidth = 1f
            )
            drawLine(
                color = TacticalBorder.copy(alpha = 0.8f),
                start = Offset(cx, cy - maxRadiusPx),
                end = Offset(cx, cy + maxRadiusPx),
                strokeWidth = 1f
            )

            // 360-deg LiDAR scan points (0 deg = +X front = screen up)
            val ranges = telemetry?.ranges.orEmpty()
            for (deg in ranges.indices) {
                val dist = ranges[deg].toFloat()
                if (dist in 0.05f..3.2f) {
                    val clampedDist = dist.coerceAtMost(rangeMaxMeters)
                    val rPx = (clampedDist / rangeMaxMeters) * maxRadiusPx
                    val rad = Math.toRadians(deg.toDouble())
                    // Body +X -> screen up (-Y), Body +Y -> screen left (-X)
                    val px = cx - (rPx * sin(rad)).toFloat()
                    val py = cy - (rPx * cos(rad)).toFloat()
                    val color = when {
                        dist < 0.35f -> TacticalRedBright
                        dist < 0.70f -> TacticalAmber
                        else -> TacticalEmerald
                    }
                    drawCircle(color = color, radius = 2.6f, center = Offset(px, py))
                }
            }

            // Robot pose chevron (+X front = screen up matching body-frame LiDAR scan)
            val pose: MapPose? = telemetry?.mapRobotPose
            val chevronRadius = 11f
            val tipAngle = -Math.PI / 2.0
            val path = Path().apply {
                val tipX = cx + (chevronRadius * cos(tipAngle)).toFloat()
                val tipY = cy + (chevronRadius * sin(tipAngle)).toFloat()
                val leftX = cx + (chevronRadius * 0.75f * cos(tipAngle + 2.4)).toFloat()
                val leftY = cy + (chevronRadius * 0.75f * sin(tipAngle + 2.4)).toFloat()
                val rightX = cx + (chevronRadius * 0.75f * cos(tipAngle - 2.4)).toFloat()
                val rightY = cy + (chevronRadius * 0.75f * sin(tipAngle - 2.4)).toFloat()
                moveTo(tipX, tipY)
                lineTo(leftX, leftY)
                lineTo(rightX, rightY)
                close()
            }
            drawPath(path = path, color = TacticalCyan)

            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#94A3B8")
                    textSize = 22f
                    isAntiAlias = true
                }
                val hdgLabel = if (pose != null) {
                    val deg = Math.toDegrees(pose.th).roundToInt()
                    "+X FWD ($deg°)"
                } else {
                    "+X FWD"
                }
                drawText(hdgLabel, cx + 6f, cy - maxRadiusPx + 18f, paint)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .align(Alignment.TopStart),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "2.0m LIDAR / SLAM",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TacticalTextMuted
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (mapStale) {
                    StaleBadge("MAP STALE")
                }
                if (teleStale) {
                    StaleBadge("TELE STALE")
                }
            }
        }
    }
}

@Composable
private fun StaleBadge(text: String) {
    Box(
        modifier = Modifier
            .background(TacticalAmber.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = TacticalAmber
        )
    }
}

private fun rasterizeOccupancyGrid(map: OccupancyMap): ImageBitmap {
    val w = map.width
    val h = map.height
    val pixels = IntArray(w * h)
    val data = map.data
    val occupiedColor = 0xFF00E5FF.toInt()
    val uncertainColor = 0xFF1E3A5F.toInt()
    val freeColor = 0xFF0F1D32.toInt()
    val unknownColor = 0x00000000

    for (row in 0 until h) {
        val dstRow = h - 1 - row
        val srcOffset = row * w
        val dstOffset = dstRow * w
        for (col in 0 until w) {
            val v = data[srcOffset + col]
            pixels[dstOffset + col] = when {
                v > 50 -> occupiedColor
                v > 0 -> uncertainColor
                v == 0 -> freeColor
                else -> unknownColor
            }
        }
    }
    val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    return bmp.asImageBitmap()
}
