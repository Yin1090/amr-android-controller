package com.yin1090.amrcontroller.model

enum class DriveDirection {
    F, B, L, R, FL, FR, BL, BR
}

enum class ArmState {
    DISARMED, ARMED, FAULT
}

enum class ConnectionStatus {
    IDLE, CONNECTING, CONNECTED, STALE, DISCONNECTED, DEMO
}

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val message: String) : ValidationResult
}

data class ConnectionConfig(
    val host: String = DEFAULT_HOST,
    val apiPort: Int = DEFAULT_API_PORT,
    val camPort: Int = DEFAULT_CAM_PORT,
    val demoMode: Boolean = false,
    val authToken: String? = null
) {
    val normalizedHost: String
        get() {
            var h = host.trim()
            if (h.startsWith("http://", ignoreCase = true)) {
                h = h.substring(7)
            } else if (h.startsWith("https://", ignoreCase = true)) {
                h = h.substring(8)
            }
            h = h.substringBefore('/').trim()
            if (!h.startsWith("[") && h.count { it == ':' } == 1) {
                val portPart = h.substringAfter(':')
                if (portPart.isNotEmpty() && portPart.all { it.isDigit() }) {
                    h = h.substringBefore(':')
                }
            }
            return h
        }

    fun formattedHostForUrl(): String {
        val h = normalizedHost
        return if (h.contains(':') && !h.startsWith("[")) "[$h]" else h
    }

    fun validate(): ValidationResult {
        val clean = normalizedHost
        if (clean.isEmpty() || clean.any { it.isWhitespace() }) {
            return ValidationResult.Invalid("Host cannot be empty or contain whitespace.")
        }
        if (apiPort !in 1..65535 || camPort !in 1..65535) {
            return ValidationResult.Invalid("API and Camera ports must be in 1..65535.")
        }
        return ValidationResult.Valid
    }

    fun apiBaseUrl(): String = "http://${formattedHostForUrl()}:$apiPort"

    fun camBaseUrl(): String = "http://${formattedHostForUrl()}:$camPort"

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_HOST_HINT = "127.0.0.1 or amr.local"
        const val DEFAULT_API_PORT = 8080
        const val DEFAULT_CAM_PORT = 8081
    }
}

data class SafetyZones(
    val front: Double = 999.0,
    val back: Double = 999.0,
    val left: Double = 999.0,
    val right: Double = 999.0,
    val frontLeft: Double = 999.0,
    val frontRight: Double = 999.0,
    val backLeft: Double = 999.0,
    val backRight: Double = 999.0
) {
    fun distanceFor(direction: DriveDirection): Double = when (direction) {
        DriveDirection.F -> front
        DriveDirection.B -> back
        DriveDirection.L -> left
        DriveDirection.R -> right
        DriveDirection.FL -> frontLeft
        DriveDirection.FR -> frontRight
        DriveDirection.BL -> backLeft
        DriveDirection.BR -> backRight
    }

    companion object {
        val ALL_CLEAR = SafetyZones()
        val ALL_BLOCKED = SafetyZones(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

data class RobotPoseAndWheels(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val th: Double = 0.0,
    val leftRpm: Double = 0.0,
    val rightRpm: Double = 0.0
)

data class ControlStatus(
    val armed: Boolean = false,
    val fault: String? = null
)

data class LidarHealth(
    val connected: Boolean = false,
    val ageSeconds: Double = Double.POSITIVE_INFINITY
)

data class MapPose(
    val x: Double,
    val y: Double,
    val th: Double
)

data class TelemetrySnapshot(
    val status: String,
    val minDistanceM: Double,
    val minAngleDeg: Double,
    val robot: RobotPoseAndWheels,
    val control: ControlStatus,
    val lidar: LidarHealth,
    val ranges: List<Double>,
    val mapRobotPose: MapPose?,
    val zones: SafetyZones,
    val receivedAtMs: Long
)

data class OccupancyMap(
    val width: Int,
    val height: Int,
    val resolutionM: Float,
    val originX: Float,
    val originY: Float,
    val originYaw: Float,
    val data: IntArray,
    val receivedAtMs: Long
) {
    fun isValid(): Boolean =
        width > 0 &&
            height > 0 &&
            width.toLong() * height.toLong() <= MAX_CELLS &&
            data.size == width * height &&
            resolutionM.isFinite() &&
            resolutionM > 0f &&
            originX.isFinite() &&
            originY.isFinite() &&
            originYaw.isFinite()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OccupancyMap) return false
        return width == other.width &&
            height == other.height &&
            resolutionM == other.resolutionM &&
            originX == other.originX &&
            originY == other.originY &&
            originYaw == other.originYaw &&
            receivedAtMs == other.receivedAtMs &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + resolutionM.hashCode()
        result = 31 * result + originX.hashCode()
        result = 31 * result + originY.hashCode()
        result = 31 * result + originYaw.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + receivedAtMs.hashCode()
        return result
    }

    companion object {
        const val MAX_CELLS = 4_000_000L
    }
}

data class CmdResponse(
    val httpCode: Int,
    val status: String,
    val reason: String? = null,
    val blockers: List<String> = emptyList()
) {
    val isSent: Boolean
        get() = status.equals("SENT", ignoreCase = true) ||
            status.equals("OK", ignoreCase = true)
}

data class PidGains(
    val kp: Float = DEFAULT_KP,
    val ki: Float = DEFAULT_KI,
    val kd: Float = DEFAULT_KD
) {
    fun isValid(): Boolean =
        kp.isFinite() && ki.isFinite() && kd.isFinite() &&
            kp >= 0f && ki >= 0f && kd >= 0f

    companion object {
        const val DEFAULT_KP = 0.5f
        const val DEFAULT_KI = 1.5f
        const val DEFAULT_KD = 0.01f
    }
}

data class TwistCommand(
    val linearMps: Double,
    val angularRadPerSec: Double
)

data class WheelRpmPair(
    val leftRpm: Double,
    val rightRpm: Double
)

data class CockpitUiState(
    val config: ConnectionConfig = ConnectionConfig(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.IDLE,
    val armState: ArmState = ArmState.DISARMED,
    val stopPending: Boolean = false,
    val demoMode: Boolean = false,
    val lidarOk: Boolean = false,
    val telemetryStale: Boolean = false,
    val telemetryAgeBadgeStale: Boolean = false,
    val mapStale: Boolean = true,
    val dpadLocked: Boolean = true,
    val desiredRpm: Int = 40,
    val effectiveRpm: Int? = null,
    val pidGains: PidGains = PidGains(),
    val activeDirection: DriveDirection? = null,
    val blockedDirections: Set<DriveDirection> = emptySet(),
    val commandedV: Double = 0.0,
    val commandedW: Double = 0.0,
    val epoch: Long = 1L,
    val seq: Long = 0L,
    val lastTelemetryAtMs: Long = 0L,
    val lastMapAtMs: Long = 0L,
    val latestTelemetry: TelemetrySnapshot? = null,
    val latestMap: OccupancyMap? = null,
    val interlockWarning: String? = null,
    val statusMessage: String? = null,
    val lastCommandNotice: String? = null,
    val settingsOpen: Boolean = false,
    val missionOpen: Boolean = false
)
