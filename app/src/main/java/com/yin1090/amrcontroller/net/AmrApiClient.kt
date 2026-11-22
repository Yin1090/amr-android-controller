package com.yin1090.amrcontroller.net

import com.yin1090.amrcontroller.domain.Kinematics
import com.yin1090.amrcontroller.model.CmdResponse
import com.yin1090.amrcontroller.model.ConnectionConfig
import com.yin1090.amrcontroller.model.ControlStatus
import com.yin1090.amrcontroller.model.LidarHealth
import com.yin1090.amrcontroller.model.MapPose
import com.yin1090.amrcontroller.model.OccupancyMap
import com.yin1090.amrcontroller.model.PidGains
import com.yin1090.amrcontroller.model.RobotPoseAndWheels
import com.yin1090.amrcontroller.model.SafetyZones
import com.yin1090.amrcontroller.model.TelemetrySnapshot
import com.yin1090.amrcontroller.model.ValidationResult
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T, val httpCode: Int = 200) : ApiResult<T>
    data class HttpError(
        val httpCode: Int,
        val reason: String,
        val rawBody: String = ""
    ) : ApiResult<Nothing>
    data class NetworkError(
        val message: String,
        val cause: Throwable? = null
    ) : ApiResult<Nothing>
    data class ValidationError(val message: String) : ApiResult<Nothing>
}

class AmrApiClient(
    private val transport: HttpTransport = OkHttpTransport()
) {
    suspend fun fetchTelemetry(
        config: ConnectionConfig,
        timeoutMs: Int = TIMEOUT_TELEMETRY_COCKPIT_MS,
        nowMs: Long = System.currentTimeMillis()
    ): ApiResult<TelemetrySnapshot> {
        val validation = config.validate()
        if (validation is ValidationResult.Invalid) {
            return ApiResult.ValidationError(validation.message)
        }
        val url = "${config.apiBaseUrl()}/api/telemetry"
        val response = safeGet(url, timeoutMs, authHeaders(config))
            ?: return ApiResult.NetworkError("Request failed")

        return when (response) {
            is TransportOutcome.Err -> ApiResult.NetworkError(response.message, response.cause)
            is TransportOutcome.Ok -> {
                if (response.http.code != 200) {
                    val reason = extractReasonOrStatus(response.http.body)
                        ?: "HTTP ${response.http.code}"
                    return ApiResult.HttpError(response.http.code, reason, response.http.body)
                }
                parseTelemetry(response.http.body, nowMs)
            }
        }
    }

    suspend fun fetchFullMap(
        config: ConnectionConfig,
        nowMs: Long = System.currentTimeMillis()
    ): ApiResult<OccupancyMap> {
        val validation = config.validate()
        if (validation is ValidationResult.Invalid) {
            return ApiResult.ValidationError(validation.message)
        }
        val url = "${config.apiBaseUrl()}/api/map_full"
        val outcome = safeGet(url, TIMEOUT_MAP_MS, authHeaders(config))
        return when (outcome) {
            is TransportOutcome.Err -> ApiResult.NetworkError(outcome.message, outcome.cause)
            is TransportOutcome.Ok -> {
                if (outcome.http.code != 200) {
                    val reason = extractReasonOrStatus(outcome.http.body)
                        ?: "HTTP ${outcome.http.code}"
                    return ApiResult.HttpError(outcome.http.code, reason, outcome.http.body)
                }
                parseFullMap(outcome.http.body, nowMs)
            }
            null -> ApiResult.NetworkError("Request failed")
        }
    }

    suspend fun sendCommand(
        config: ConnectionConfig,
        command: String,
        epoch: Long,
        seq: Long
    ): ApiResult<CmdResponse> {
        val validation = config.validate()
        if (validation is ValidationResult.Invalid) {
            return ApiResult.ValidationError(validation.message)
        }
        if (command.isBlank()) {
            return ApiResult.ValidationError("Command cannot be blank.")
        }
        val encodedCmd = encodeQueryValue(command)
        val url = "${config.apiBaseUrl()}/api/cmd?val=$encodedCmd&epoch=$epoch&seq=$seq"
        val outcome = safeGet(url, TIMEOUT_CMD_MS, authHeaders(config))
        return when (outcome) {
            is TransportOutcome.Err -> ApiResult.NetworkError(outcome.message, outcome.cause)
            is TransportOutcome.Ok -> parseCmdResponse(outcome.http.code, outcome.http.body)
            null -> ApiResult.NetworkError("Request failed")
        }
    }

    suspend fun setAutoConfig(
        config: ConnectionConfig,
        speedRpm: Int,
        turnRpm: Int = speedRpm
    ): ApiResult<Int> {
        val validation = config.validate()
        if (validation is ValidationResult.Invalid) {
            return ApiResult.ValidationError(validation.message)
        }
        if (speedRpm !in Kinematics.MIN_RPM..Kinematics.MAX_RPM ||
            turnRpm !in Kinematics.MIN_RPM..Kinematics.MAX_RPM
        ) {
            return ApiResult.ValidationError(
                "RPM must be in ${Kinematics.MIN_RPM}..${Kinematics.MAX_RPM}."
            )
        }
        val url = "${config.apiBaseUrl()}/api/auto_config?speed=$speedRpm&turn=$turnRpm"
        val outcome = safeGet(url, TIMEOUT_CONFIG_MS, authHeaders(config))
        return when (outcome) {
            is TransportOutcome.Err -> ApiResult.NetworkError(outcome.message, outcome.cause)
            is TransportOutcome.Ok -> {
                if (outcome.http.code == 200) {
                    ApiResult.Success(speedRpm, 200)
                } else {
                    val reason = extractReasonOrStatus(outcome.http.body)
                        ?: "HTTP ${outcome.http.code}"
                    ApiResult.HttpError(outcome.http.code, reason, outcome.http.body)
                }
            }
            null -> ApiResult.NetworkError("Request failed")
        }
    }

    suspend fun applyPid(
        config: ConnectionConfig,
        gains: PidGains
    ): ApiResult<PidGains> {
        val validation = config.validate()
        if (validation is ValidationResult.Invalid) {
            return ApiResult.ValidationError(validation.message)
        }
        if (!gains.isValid()) {
            return ApiResult.ValidationError("PID gains must be finite non-negative numbers.")
        }
        val kpStr = formatPidFloat(gains.kp)
        val kiStr = formatPidFloat(gains.ki)
        val kdStr = formatPidFloat(gains.kd)
        val url = "${config.apiBaseUrl()}/api/pid?kp=$kpStr&ki=$kiStr&kd=$kdStr"
        val outcome = safeGet(url, TIMEOUT_CONFIG_MS, authHeaders(config))
        return when (outcome) {
            is TransportOutcome.Err -> ApiResult.NetworkError(outcome.message, outcome.cause)
            is TransportOutcome.Ok -> {
                if (outcome.http.code == 200) {
                    ApiResult.Success(gains, 200)
                } else {
                    val reason = extractReasonOrStatus(outcome.http.body)
                        ?: "HTTP ${outcome.http.code}"
                    ApiResult.HttpError(outcome.http.code, reason, outcome.http.body)
                }
            }
            null -> ApiResult.NetworkError("Request failed")
        }
    }

    suspend fun resetMap(config: ConnectionConfig): ApiResult<Unit> {
        val validation = config.validate()
        if (validation is ValidationResult.Invalid) {
            return ApiResult.ValidationError(validation.message)
        }
        val url = "${config.apiBaseUrl()}/api/reset_map"
        val outcome = safeGet(url, TIMEOUT_CONFIG_MS, authHeaders(config))
        return when (outcome) {
            is TransportOutcome.Err -> ApiResult.NetworkError(outcome.message, outcome.cause)
            is TransportOutcome.Ok -> {
                if (outcome.http.code == 200) {
                    ApiResult.Success(Unit, 200)
                } else {
                    val reason = extractReasonOrStatus(outcome.http.body)
                        ?: "HTTP ${outcome.http.code}"
                    ApiResult.HttpError(outcome.http.code, reason, outcome.http.body)
                }
            }
            null -> ApiResult.NetworkError("Request failed")
        }
    }

    suspend fun probeCameraStatus(config: ConnectionConfig): ApiResult<Boolean> {
        val validation = config.validate()
        if (validation is ValidationResult.Invalid) {
            return ApiResult.ValidationError(validation.message)
        }
        val url = "${config.camBaseUrl()}/status"
        val outcome = safeGet(url, TIMEOUT_CAMERA_STATUS_MS, emptyMap())
        return when (outcome) {
            is TransportOutcome.Err -> ApiResult.NetworkError(outcome.message, outcome.cause)
            is TransportOutcome.Ok -> {
                if (outcome.http.code == 200) {
                    ApiResult.Success(true, 200)
                } else {
                    ApiResult.HttpError(outcome.http.code, "HTTP ${outcome.http.code}", outcome.http.body)
                }
            }
            null -> ApiResult.NetworkError("Request failed")
        }
    }

    fun buildCameraStreamUrl(config: ConnectionConfig, epochMs: Long): String =
        "${config.camBaseUrl()}/stream?t=$epochMs"

    fun buildMissionUrl(config: ConnectionConfig): String =
        "${config.apiBaseUrl()}/?compact=1"

    private sealed interface TransportOutcome {
        data class Ok(val http: HttpResponse) : TransportOutcome
        data class Err(val message: String, val cause: Throwable?) : TransportOutcome
    }

    private suspend fun safeGet(
        url: String,
        timeoutMs: Int,
        headers: Map<String, String>
    ): TransportOutcome? = try {
        TransportOutcome.Ok(transport.get(url, timeoutMs, headers))
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        TransportOutcome.Err(t.message ?: t::class.java.simpleName, t)
    }

    private fun authHeaders(config: ConnectionConfig): Map<String, String> {
        val token = config.authToken?.trim().orEmpty()
        return if (token.isNotEmpty()) {
            mapOf("Authorization" to "Bearer $token")
        } else {
            emptyMap()
        }
    }

    internal fun parseTelemetry(body: String, nowMs: Long): ApiResult<TelemetrySnapshot> {
        return try {
            val root = JSONObject(body)
            val status = nonNullStringOr(root, "status", "UNKNOWN")
            val minM = finiteDoubleOr(root, "min_m", 9.99)
            val minAng = finiteDoubleOr(root, "min_ang", 0.0)

            val robotObj = root.optJSONObject("robot")
            val robot = RobotPoseAndWheels(
                x = finiteDoubleOr(robotObj, "x", 0.0),
                y = finiteDoubleOr(robotObj, "y", 0.0),
                th = finiteDoubleOr(robotObj, "th", 0.0),
                leftRpm = finiteDoubleOr(robotObj, "l_rpm", 0.0),
                rightRpm = finiteDoubleOr(robotObj, "r_rpm", 0.0)
            )

            val controlObj = root.optJSONObject("control")
            val armed = controlObj?.optBoolean("armed", false) ?: false
            val rawFault = if (controlObj == null || controlObj.isNull("fault")) {
                null
            } else {
                controlObj.optString("fault", "").trim().takeIf {
                    it.isNotEmpty() && !it.equals("null", ignoreCase = true)
                }
            }
            val control = ControlStatus(armed = armed, fault = rawFault)

            val lidarObj = root.optJSONObject("lidar")
            val lidarConnected = lidarObj?.optBoolean("connected", false) ?: false
            val lidarAge = if (lidarObj == null || lidarObj.isNull("age_s")) {
                Double.POSITIVE_INFINITY
            } else {
                val parsed = lidarObj.optDouble("age_s", Double.POSITIVE_INFINITY)
                if (parsed.isFinite()) parsed else Double.POSITIVE_INFINITY
            }
            val lidar = LidarHealth(connected = lidarConnected, ageSeconds = lidarAge)

            val rangesArr = root.optJSONArray("ranges")
            val ranges = if (rangesArr != null) {
                List(rangesArr.length()) { idx ->
                    val v = rangesArr.optDouble(idx, 0.0)
                    if (v.isFinite() && v >= 0.0) v else 0.0
                }
            } else {
                emptyList()
            }

            val mapInfoObj = root.optJSONObject("map_info")
            val poseArr = mapInfoObj?.optJSONArray("robot_pose")
            val mapPose = if (poseArr != null && poseArr.length() >= 3) {
                val px = poseArr.optDouble(0, Double.NaN)
                val py = poseArr.optDouble(1, Double.NaN)
                val pth = poseArr.optDouble(2, Double.NaN)
                if (px.isFinite() && py.isFinite() && pth.isFinite()) {
                    MapPose(px, py, pth)
                } else {
                    null
                }
            } else {
                null
            }

            val zonesObj = root.optJSONObject("zones")
            val zones = SafetyZones(
                front = failClosedZoneCm(zonesObj, "front"),
                back = failClosedZoneCm(zonesObj, "back"),
                left = failClosedZoneCm(zonesObj, "left"),
                right = failClosedZoneCm(zonesObj, "right"),
                frontLeft = failClosedZoneCm(zonesObj, "front_left"),
                frontRight = failClosedZoneCm(zonesObj, "front_right"),
                backLeft = failClosedZoneCm(zonesObj, "back_left"),
                backRight = failClosedZoneCm(zonesObj, "back_right")
            )

            ApiResult.Success(
                TelemetrySnapshot(
                    status = status,
                    minDistanceM = minM,
                    minAngleDeg = minAng,
                    robot = robot,
                    control = control,
                    lidar = lidar,
                    ranges = ranges,
                    mapRobotPose = mapPose,
                    zones = zones,
                    receivedAtMs = nowMs
                ),
                httpCode = 200
            )
        } catch (t: Throwable) {
            ApiResult.ValidationError("Malformed telemetry JSON: ${t.message}")
        }
    }

    internal fun parseFullMap(body: String, nowMs: Long): ApiResult<OccupancyMap> {
        return try {
            val root = JSONObject(body)
            val w = root.optInt("w", -1)
            val h = root.optInt("h", -1)
            val res = root.optDouble("res", Double.NaN).toFloat()
            val ox = root.optDouble("ox", 0.0).toFloat()
            val oy = root.optDouble("oy", 0.0).toFloat()
            val yaw = root.optDouble("yaw", 0.0).toFloat()

            if (w <= 0 || h <= 0) {
                return ApiResult.ValidationError("Invalid map dimensions: ${w}x${h}.")
            }
            val totalCells = w.toLong() * h.toLong()
            if (totalCells > OccupancyMap.MAX_CELLS) {
                return ApiResult.ValidationError(
                    "Map cell count $totalCells exceeds limit ${OccupancyMap.MAX_CELLS}."
                )
            }
            if (!res.isFinite() || res <= 0f || !ox.isFinite() || !oy.isFinite() || !yaw.isFinite()) {
                return ApiResult.ValidationError("Invalid map resolution or origin metadata.")
            }

            val dataArr = root.optJSONArray("data")
                ?: return ApiResult.ValidationError("Missing map data array.")
            val expected = totalCells.toInt()
            if (dataArr.length() != expected) {
                return ApiResult.ValidationError(
                    "Map data length ${dataArr.length()} does not match w*h ($expected)."
                )
            }

            val cells = IntArray(expected) { idx -> dataArr.optInt(idx, -1) }
            val map = OccupancyMap(
                width = w,
                height = h,
                resolutionM = res,
                originX = ox,
                originY = oy,
                originYaw = yaw,
                data = cells,
                receivedAtMs = nowMs
            )
            if (!map.isValid()) {
                ApiResult.ValidationError("Occupancy map failed validation.")
            } else {
                ApiResult.Success(map, 200)
            }
        } catch (t: Throwable) {
            ApiResult.ValidationError("Malformed map_full JSON: ${t.message}")
        }
    }

    internal fun parseCmdResponse(httpCode: Int, body: String): ApiResult<CmdResponse> {
        return try {
            val trimmed = body.trim()
            if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                val defaultStatus = if (httpCode == 200) "SENT" else "FAILED"
                val status = nonNullStringOr(root, "status", defaultStatus)
                val reason = if (!root.has("reason") || root.isNull("reason")) {
                    null
                } else {
                    root.optString("reason", "").trim().takeIf {
                        it.isNotEmpty() && !it.equals("null", ignoreCase = true)
                    }
                }
                val blockersArr = root.optJSONArray("blockers")
                val blockers = if (blockersArr != null) {
                    List(blockersArr.length()) { idx ->
                        if (blockersArr.isNull(idx)) "" else blockersArr.optString(idx, "").trim()
                    }.filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                } else {
                    emptyList()
                }
                ApiResult.Success(
                    CmdResponse(
                        httpCode = httpCode,
                        status = status,
                        reason = reason,
                        blockers = blockers
                    ),
                    httpCode = httpCode
                )
            } else if (httpCode in 200..299) {
                ApiResult.Success(CmdResponse(httpCode = httpCode, status = "SENT"), httpCode = httpCode)
            } else {
                ApiResult.HttpError(httpCode, "HTTP $httpCode", body)
            }
        } catch (t: Throwable) {
            if (httpCode in 200..299) {
                ApiResult.ValidationError("Malformed command JSON: ${t.message}")
            } else {
                ApiResult.HttpError(httpCode, "HTTP $httpCode", body)
            }
        }
    }

    private fun nonNullStringOr(obj: JSONObject?, key: String, fallback: String): String {
        if (obj == null || !obj.has(key) || obj.isNull(key)) {
            return fallback
        }
        val raw = obj.optString(key, "").trim()
        return if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) fallback else raw
    }

    private fun failClosedZoneCm(zonesObj: JSONObject?, key: String): Double {
        if (zonesObj == null || !zonesObj.has(key) || zonesObj.isNull(key)) {
            return 0.0
        }
        val value = zonesObj.optDouble(key, Double.NaN)
        return if (value.isFinite() && value >= 0.0) value else 0.0
    }

    private fun finiteDoubleOr(obj: JSONObject?, key: String, fallback: Double): Double {
        if (obj == null || !obj.has(key) || obj.isNull(key)) {
            return fallback
        }
        val v = obj.optDouble(key, Double.NaN)
        return if (v.isFinite()) v else fallback
    }

    private fun extractReasonOrStatus(body: String): String? {
        return try {
            val trimmed = body.trim()
            if (!trimmed.startsWith("{")) return null
            val obj = JSONObject(trimmed)
            val reason = if (!obj.has("reason") || obj.isNull("reason")) {
                null
            } else {
                obj.optString("reason", "").trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            }
            val error = if (!obj.has("error") || obj.isNull("error")) {
                null
            } else {
                obj.optString("error", "").trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            }
            val status = if (!obj.has("status") || obj.isNull("status")) {
                null
            } else {
                obj.optString("status", "").trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            }
            reason ?: error ?: status
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        const val TIMEOUT_CONNECT_MS = 12_000
        const val TIMEOUT_PREFLIGHT_MS = 6_000
        const val TIMEOUT_TELEMETRY_COCKPIT_MS = 4_000
        const val TIMEOUT_MAP_MS = 8_000
        const val TIMEOUT_CMD_MS = 5_000
        const val TIMEOUT_CONFIG_MS = 8_000
        const val TIMEOUT_CAMERA_STATUS_MS = 4_000

        fun encodeQueryValue(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

        fun formatPidFloat(value: Float): String {
            val clean = if (!value.isFinite() || abs(value) < 1e-6f) 0.0f else value
            return String.format(Locale.US, "%.4f", clean)
                .trimEnd('0')
                .let { if (it.endsWith('.')) "${it}0" else it }
        }
    }
}
