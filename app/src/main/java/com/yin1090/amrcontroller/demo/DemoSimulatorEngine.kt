package com.yin1090.amrcontroller.demo

import com.yin1090.amrcontroller.domain.Kinematics
import com.yin1090.amrcontroller.model.ControlStatus
import com.yin1090.amrcontroller.model.DriveDirection
import com.yin1090.amrcontroller.model.LidarHealth
import com.yin1090.amrcontroller.model.MapPose
import com.yin1090.amrcontroller.model.OccupancyMap
import com.yin1090.amrcontroller.model.RobotPoseAndWheels
import com.yin1090.amrcontroller.model.SafetyZones
import com.yin1090.amrcontroller.model.TelemetrySnapshot
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

class DemoSimulatorEngine {
    private var x: Double = 0.0
    private var y: Double = 0.0
    private var th: Double = 0.0
    private var targetV: Double = 0.0
    private var targetW: Double = 0.0
    private var armed: Boolean = false
    private var fault: String? = null
    private var manualZoneOverrides: MutableMap<DriveDirection, Double> = mutableMapOf()
    private var virtualObstacle: VirtualObstacle? = null

    private val mapWidth = 60
    private val mapHeight = 60
    private val mapResolution = 0.05f
    private val mapOriginX = -1.5f
    private val mapOriginY = -1.5f

    data class VirtualObstacle(
        val x: Double,
        val y: Double,
        val radiusM: Double = 0.14
    )

    fun setArmed(value: Boolean) {
        armed = value
        if (!value) {
            targetV = 0.0
            targetW = 0.0
        }
    }

    fun setFault(reason: String?) {
        fault = reason
    }

    fun setTargetTwist(v: Double, w: Double) {
        targetV = v
        targetW = w
    }

    fun setZoneOverride(direction: DriveDirection, distanceCm: Double) {
        manualZoneOverrides[direction] = distanceCm
    }

    fun clearZoneOverrides() {
        manualZoneOverrides.clear()
    }

    fun placeVirtualObstacle(obsX: Double, obsY: Double, radiusM: Double = 0.14) {
        virtualObstacle = VirtualObstacle(obsX, obsY, radiusM)
    }

    fun clearVirtualObstacle() {
        virtualObstacle = null
    }

    fun resetPoseAndMap() {
        x = 0.0
        y = 0.0
        th = 0.0
        targetV = 0.0
        targetW = 0.0
        manualZoneOverrides.clear()
        virtualObstacle = null
    }

    fun step(dtSeconds: Double, nowMs: Long): TelemetrySnapshot {
        val dt = dtSeconds.coerceIn(0.0, 1.0)
        if (armed && fault == null) {
            val nextX = x + targetV * cos(th) * dt
            val nextY = y + targetV * sin(th) * dt
            // Confine robot within simulated 2.6m x 2.6m arena
            x = nextX.coerceIn(-1.15, 1.15)
            y = nextY.coerceIn(-1.15, 1.15)
            th = normalizeAngle(th + targetW * dt)
        }

        val wheels = if (armed) Kinematics.twistToWheelRpm(targetV, targetW) else Kinematics.twistToWheelRpm(0.0, 0.0)
        val ranges = compute360Ranges()
        val zones = computeZones(ranges)

        var minDistM = 12.0
        var minAng = 0.0
        for (deg in ranges.indices) {
            val d = ranges[deg]
            if (d in 0.05..12.0 && d < minDistM) {
                minDistM = d
                minAng = deg.toDouble()
            }
        }

        val minZoneCm = listOf(
            zones.front,
            zones.back,
            zones.left,
            zones.right,
            zones.frontLeft,
            zones.frontRight,
            zones.backLeft,
            zones.backRight
        ).minOrNull() ?: 999.0

        val safetyStatus = when {
            minZoneCm < 40.0 -> "DANGER_STOP"
            minZoneCm < 70.0 -> "WARNING"
            else -> "SAFE"
        }

        return TelemetrySnapshot(
            status = safetyStatus,
            minDistanceM = minDistM,
            minAngleDeg = minAng,
            robot = RobotPoseAndWheels(
                x = x,
                y = y,
                th = th,
                leftRpm = wheels.leftRpm,
                rightRpm = wheels.rightRpm
            ),
            control = ControlStatus(armed = armed, fault = fault),
            lidar = LidarHealth(connected = true, ageSeconds = 0.05),
            ranges = ranges,
            mapRobotPose = MapPose(x = x, y = y, th = th),
            zones = zones,
            receivedAtMs = nowMs
        )
    }

    fun buildOccupancyMap(nowMs: Long): OccupancyMap {
        val cells = IntArray(mapWidth * mapHeight) { 0 }
        for (row in 0 until mapHeight) {
            for (col in 0 until mapWidth) {
                val idx = row * mapWidth + col
                val border = row == 0 || row == mapHeight - 1 || col == 0 || col == mapWidth - 1
                if (border) {
                    cells[idx] = 100
                } else {
                    val wx = mapOriginX + (col + 0.5f) * mapResolution
                    val wy = mapOriginY + (row + 0.5f) * mapResolution
                    val obs = virtualObstacle
                    if (obs != null && hypot((wx - obs.x).toDouble(), (wy - obs.y).toDouble()) <= obs.radiusM) {
                        cells[idx] = 95
                    } else if (hypot(wx.toDouble(), wy.toDouble()) > 1.35) {
                        cells[idx] = 25
                    }
                }
            }
        }
        return OccupancyMap(
            width = mapWidth,
            height = mapHeight,
            resolutionM = mapResolution,
            originX = mapOriginX,
            originY = mapOriginY,
            originYaw = 0f,
            data = cells,
            receivedAtMs = nowMs
        )
    }

    private fun compute360Ranges(): List<Double> {
        val halfBox = 1.45
        val list = ArrayList<Double>(360)
        for (deg in 0 until 360) {
            val worldAngle = th + Math.toRadians(deg.toDouble())
            val dx = cos(worldAngle)
            val dy = sin(worldAngle)

            var best = 12.0
            if (kotlin.math.abs(dx) > 1e-6) {
                val tx1 = (halfBox - x) / dx
                val tx2 = (-halfBox - x) / dx
                if (tx1 > 0.05) best = minOf(best, tx1)
                if (tx2 > 0.05) best = minOf(best, tx2)
            }
            if (kotlin.math.abs(dy) > 1e-6) {
                val ty1 = (halfBox - y) / dy
                val ty2 = (-halfBox - y) / dy
                if (ty1 > 0.05) best = minOf(best, ty1)
                if (ty2 > 0.05) best = minOf(best, ty2)
            }

            val obs = virtualObstacle
            if (obs != null) {
                val ocx = obs.x - x
                val ocy = obs.y - y
                val proj = ocx * dx + ocy * dy
                if (proj > 0.0) {
                    val perpSq = (ocx * ocx + ocy * ocy) - proj * proj
                    val rSq = obs.radiusM * obs.radiusM
                    if (perpSq <= rSq) {
                        val hit = proj - kotlin.math.sqrt(rSq - perpSq)
                        if (hit > 0.05) best = minOf(best, hit)
                    }
                }
            }
            list.add(best)
        }
        return list
    }

    private fun computeZones(ranges: List<Double>): SafetyZones {
        fun sectorMinCm(centerDeg: Int): Double {
            var minM = 9.99
            for (offset in -22..22) {
                val idx = ((centerDeg + offset) % 360 + 360) % 360
                val d = ranges[idx]
                if (d > 0.05 && d < minM) {
                    minM = d
                }
            }
            return (minM * 100.0).coerceAtMost(999.0)
        }

        fun pick(dir: DriveDirection, centerDeg: Int): Double =
            manualZoneOverrides[dir] ?: sectorMinCm(centerDeg)

        return SafetyZones(
            front = pick(DriveDirection.F, 0),
            frontLeft = pick(DriveDirection.FL, 45),
            left = pick(DriveDirection.L, 90),
            backLeft = pick(DriveDirection.BL, 135),
            back = pick(DriveDirection.B, 180),
            backRight = pick(DriveDirection.BR, 225),
            right = pick(DriveDirection.R, 270),
            frontRight = pick(DriveDirection.FR, 315)
        )
    }

    private fun normalizeAngle(angle: Double): Double {
        var a = angle
        while (a > PI) a -= 2.0 * PI
        while (a < -PI) a += 2.0 * PI
        return a
    }
}
