package com.yin1090.amrcontroller.domain

import com.yin1090.amrcontroller.model.ArmState
import com.yin1090.amrcontroller.model.DriveDirection
import com.yin1090.amrcontroller.model.LidarHealth
import com.yin1090.amrcontroller.model.SafetyZones
import java.util.Locale

sealed interface InterlockDecision {
    data object Allowed : InterlockDecision
    data class Blocked(
        val reasonCode: String,
        val zoneLabel: String?,
        val distanceCm: Double?,
        val userMessage: String
    ) : InterlockDecision
}

data class ZoneDescriptor(
    val jsonKey: String,
    val label: String,
    val recoveryHint: String
)

object SafetyInterlock {
    const val OBSTACLE_STOP_THRESHOLD_CM = 40.0
    const val LIDAR_MAX_AGE_SECONDS = 0.5
    const val TELEMETRY_WATCHDOG_MS = 1_500L
    const val MAP_STALE_MS = 2_500L
    const val TELEMETRY_STALE_BADGE_MS = 3_000L

    fun isLidarHealthy(lidar: LidarHealth): Boolean =
        lidar.connected &&
            lidar.ageSeconds.isFinite() &&
            lidar.ageSeconds >= 0.0 &&
            lidar.ageSeconds < LIDAR_MAX_AGE_SECONDS

    fun isTelemetryWatchdogExpired(lastTelemetryAtMs: Long, nowMs: Long): Boolean =
        lastTelemetryAtMs <= 0L || (nowMs - lastTelemetryAtMs > TELEMETRY_WATCHDOG_MS)

    fun isTelemetryBadgeStale(lastTelemetryAtMs: Long, nowMs: Long): Boolean =
        lastTelemetryAtMs <= 0L || (nowMs - lastTelemetryAtMs > TELEMETRY_STALE_BADGE_MS)

    fun isMapStale(lastMapAtMs: Long, nowMs: Long): Boolean =
        lastMapAtMs <= 0L || (nowMs - lastMapAtMs > MAP_STALE_MS)

    fun zoneDescriptor(direction: DriveDirection): ZoneDescriptor = when (direction) {
        DriveDirection.F -> ZoneDescriptor("front", "FRONT", "Reverse only.")
        DriveDirection.B -> ZoneDescriptor("back", "BACK", "Forward only.")
        DriveDirection.L -> ZoneDescriptor("left", "LEFT", "Steer right only.")
        DriveDirection.R -> ZoneDescriptor("right", "RIGHT", "Steer left only.")
        DriveDirection.FL -> ZoneDescriptor("front_left", "FRONT-LEFT", "Obstacle front-left.")
        DriveDirection.FR -> ZoneDescriptor("front_right", "FRONT-RIGHT", "Obstacle front-right.")
        DriveDirection.BL -> ZoneDescriptor("back_left", "BACK-LEFT", "Obstacle back-left.")
        DriveDirection.BR -> ZoneDescriptor("back_right", "BACK-RIGHT", "Obstacle back-right.")
    }

    fun isZoneBlocked(distanceCm: Double): Boolean =
        !distanceCm.isFinite() || distanceCm < 0.0 || distanceCm < OBSTACLE_STOP_THRESHOLD_CM

    fun blockedDirections(zones: SafetyZones): Set<DriveDirection> =
        DriveDirection.entries.filterTo(mutableSetOf()) { dir ->
            isZoneBlocked(zones.distanceFor(dir))
        }

    fun evaluateDrive(
        direction: DriveDirection,
        armState: ArmState,
        stopPending: Boolean,
        demoMode: Boolean,
        lidarOk: Boolean,
        telemetryStale: Boolean,
        zones: SafetyZones
    ): InterlockDecision {
        if (stopPending) {
            return InterlockDecision.Blocked(
                reasonCode = "STOP_PENDING",
                zoneLabel = null,
                distanceCm = null,
                userMessage = "Emergency STOP pending."
            )
        }
        if (armState == ArmState.FAULT) {
            return InterlockDecision.Blocked(
                reasonCode = "FAULT_ACTIVE",
                zoneLabel = null,
                distanceCm = null,
                userMessage = "Controller FAULT active. Clear fault before driving."
            )
        }
        if (armState != ArmState.ARMED) {
            return InterlockDecision.Blocked(
                reasonCode = "NOT_ARMED",
                zoneLabel = null,
                distanceCm = null,
                userMessage = "Robot is DISARMED. Press ARM to enable drive."
            )
        }
        if (!demoMode && (telemetryStale || !lidarOk)) {
            return InterlockDecision.Blocked(
                reasonCode = "LIDAR_OR_TELEMETRY_STALE",
                zoneLabel = null,
                distanceCm = null,
                userMessage = "Drive locked: telemetry stale or LiDAR offline/stale."
            )
        }

        val descriptor = zoneDescriptor(direction)
        val distCm = zones.distanceFor(direction)
        if (isZoneBlocked(distCm)) {
            val formatted = if (distCm.isFinite() && distCm >= 0.0) {
                String.format(Locale.US, "%.1f", distCm)
            } else {
                "N/A"
            }
            return InterlockDecision.Blocked(
                reasonCode = "ZONE_OBSTACLE_TOO_CLOSE",
                zoneLabel = descriptor.label,
                distanceCm = if (distCm.isFinite()) distCm else null,
                userMessage = "Obstacle ${descriptor.label} < 40 cm ($formatted cm). ${descriptor.recoveryHint}"
            )
        }

        return InterlockDecision.Allowed
    }
}
