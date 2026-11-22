package com.yin1090.amrcontroller.data

import android.content.Context
import android.content.SharedPreferences
import com.yin1090.amrcontroller.domain.Kinematics
import com.yin1090.amrcontroller.model.ConnectionConfig
import com.yin1090.amrcontroller.model.PidGains

data class PersistedSettings(
    val host: String = "",
    val apiPort: Int = ConnectionConfig.DEFAULT_API_PORT,
    val camPort: Int = ConnectionConfig.DEFAULT_CAM_PORT,
    val rpmMax: Int = Kinematics.DEFAULT_RPM,
    val pidGains: PidGains = PidGains()
)

class AmrSettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): PersistedSettings {
        val host = prefs.getString(KEY_HOST, "")?.trim().orEmpty()
        val apiPort = prefs.getInt(KEY_API_PORT, ConnectionConfig.DEFAULT_API_PORT)
            .takeIf { it in 1..65535 } ?: ConnectionConfig.DEFAULT_API_PORT
        val camPort = prefs.getInt(KEY_CAM_PORT, ConnectionConfig.DEFAULT_CAM_PORT)
            .takeIf { it in 1..65535 } ?: ConnectionConfig.DEFAULT_CAM_PORT
        val rpmMax = Kinematics.clampRpm(prefs.getInt(KEY_RPM_MAX, Kinematics.DEFAULT_RPM))
        val kp = prefs.getFloat(KEY_PID_KP, PidGains.DEFAULT_KP)
            .takeIf { it.isFinite() && it >= 0f } ?: PidGains.DEFAULT_KP
        val ki = prefs.getFloat(KEY_PID_KI, PidGains.DEFAULT_KI)
            .takeIf { it.isFinite() && it >= 0f } ?: PidGains.DEFAULT_KI
        val kd = prefs.getFloat(KEY_PID_KD, PidGains.DEFAULT_KD)
            .takeIf { it.isFinite() && it >= 0f } ?: PidGains.DEFAULT_KD

        return PersistedSettings(
            host = host,
            apiPort = apiPort,
            camPort = camPort,
            rpmMax = rpmMax,
            pidGains = PidGains(kp = kp, ki = ki, kd = kd)
        )
    }

    fun saveConnection(host: String, apiPort: Int, camPort: Int) {
        prefs.edit()
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_API_PORT, apiPort.coerceIn(1, 65535))
            .putInt(KEY_CAM_PORT, camPort.coerceIn(1, 65535))
            .apply()
    }

    fun saveRpm(rpm: Int) {
        prefs.edit()
            .putInt(KEY_RPM_MAX, Kinematics.clampRpm(rpm))
            .apply()
    }

    fun savePid(gains: PidGains) {
        if (!gains.isValid()) return
        prefs.edit()
            .putFloat(KEY_PID_KP, gains.kp)
            .putFloat(KEY_PID_KI, gains.ki)
            .putFloat(KEY_PID_KD, gains.kd)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "amr_controller_prefs"
        private const val KEY_HOST = "host"
        private const val KEY_API_PORT = "api_port"
        private const val KEY_CAM_PORT = "cam_port"
        private const val KEY_RPM_MAX = "rpm_max"
        private const val KEY_PID_KP = "pid_kp"
        private const val KEY_PID_KI = "pid_ki"
        private const val KEY_PID_KD = "pid_kd"
    }
}
