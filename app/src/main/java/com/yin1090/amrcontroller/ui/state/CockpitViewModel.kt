package com.yin1090.amrcontroller.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yin1090.amrcontroller.data.AmrSettingsStore
import com.yin1090.amrcontroller.data.MjpegStreamClient
import com.yin1090.amrcontroller.domain.Kinematics
import com.yin1090.amrcontroller.domain.SafetyInterlock
import com.yin1090.amrcontroller.model.CockpitUiState
import com.yin1090.amrcontroller.model.ConnectionConfig
import com.yin1090.amrcontroller.model.DriveDirection
import com.yin1090.amrcontroller.model.PidGains
import com.yin1090.amrcontroller.model.ValidationResult
import com.yin1090.amrcontroller.net.AmrApiClient
import com.yin1090.amrcontroller.net.ApiResult
import com.yin1090.amrcontroller.state.CockpitStateMachine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectionFormState(
    val host: String = "",
    val apiPortText: String = ConnectionConfig.DEFAULT_API_PORT.toString(),
    val camPortText: String = ConnectionConfig.DEFAULT_CAM_PORT.toString(),
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val warningBanner: String? = null
)

data class PreflightState(
    val tick: Int = 0,
    val apiOk: Boolean = false,
    val lidarOk: Boolean = false,
    val cameraOk: Boolean = false,
    val failed: Boolean = false,
    val statusDetail: String = "Initializing preflight checks...",
    val warningBanner: String? = null
) {
    val readinessCount: Int
        get() = listOf(apiOk, lidarOk, cameraOk).count { it }
}

class CockpitViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = AmrSettingsStore(application)
    val apiClient = AmrApiClient()
    val mjpegStreamClient = MjpegStreamClient()

    private val initialSettings = settingsStore.load()

    val stateMachine = CockpitStateMachine(
        scope = viewModelScope,
        apiClient = apiClient,
        initialConfig = ConnectionConfig(
            host = initialSettings.host,
            apiPort = initialSettings.apiPort,
            camPort = initialSettings.camPort,
            demoMode = false
        ),
        initialRpm = initialSettings.rpmMax,
        initialPid = initialSettings.pidGains
    )

    val cockpitState: StateFlow<CockpitUiState> = stateMachine.state

    private val _connectionForm = MutableStateFlow(
        ConnectionFormState(
            host = initialSettings.host,
            apiPortText = initialSettings.apiPort.toString(),
            camPortText = initialSettings.camPort.toString()
        )
    )
    val connectionForm: StateFlow<ConnectionFormState> = _connectionForm.asStateFlow()

    private val _preflightState = MutableStateFlow(PreflightState())
    val preflightState: StateFlow<PreflightState> = _preflightState.asStateFlow()

    private var activeConfig: ConnectionConfig = ConnectionConfig(
        host = initialSettings.host.ifBlank { ConnectionConfig.DEFAULT_HOST },
        apiPort = initialSettings.apiPort,
        camPort = initialSettings.camPort,
        demoMode = false
    )

    private var preflightJob: Job? = null

    fun updateHost(value: String) {
        _connectionForm.update { it.copy(host = value, errorMessage = null) }
    }

    fun updateApiPort(value: String) {
        _connectionForm.update { it.copy(apiPortText = value.filter { ch -> ch.isDigit() }, errorMessage = null) }
    }

    fun updateCamPort(value: String) {
        _connectionForm.update { it.copy(camPortText = value.filter { ch -> ch.isDigit() }, errorMessage = null) }
    }

    fun connectLive(onSuccess: (demoMode: Boolean, warning: String?) -> Unit) {
        val form = _connectionForm.value
        val apiPort = form.apiPortText.toIntOrNull() ?: -1
        val camPort = form.camPortText.toIntOrNull() ?: -1
        val candidate = ConnectionConfig(
            host = form.host,
            apiPort = apiPort,
            camPort = camPort,
            demoMode = false
        )
        val validation = candidate.validate()
        if (validation is ValidationResult.Invalid) {
            _connectionForm.update { it.copy(errorMessage = validation.message) }
            return
        }

        viewModelScope.launch {
            _connectionForm.update { it.copy(busy = true, errorMessage = null, warningBanner = null) }
            when (val res = apiClient.fetchTelemetry(candidate, AmrApiClient.TIMEOUT_CONNECT_MS)) {
                is ApiResult.Success -> {
                    settingsStore.saveConnection(candidate.normalizedHost, candidate.apiPort, candidate.camPort)
                    activeConfig = candidate
                    _connectionForm.update { it.copy(busy = false) }
                    onSuccess(false, null)
                }
                is ApiResult.HttpError -> {
                    if (res.httpCode == 404) {
                        val warning = "Telemetry endpoint returned HTTP 404; proceeding without saving host config."
                        activeConfig = candidate
                        _connectionForm.update { it.copy(busy = false, warningBanner = warning) }
                        onSuccess(false, warning)
                    } else {
                        _connectionForm.update {
                            it.copy(
                                busy = false,
                                errorMessage = "Robot API returned HTTP ${res.httpCode} (${res.reason})."
                            )
                        }
                    }
                }
                is ApiResult.NetworkError -> {
                    _connectionForm.update {
                        it.copy(busy = false, errorMessage = "Connection failed: ${res.message}")
                    }
                }
                is ApiResult.ValidationError -> {
                    _connectionForm.update {
                        it.copy(busy = false, errorMessage = res.message)
                    }
                }
            }
        }
    }

    fun startOfflineDemo(onReady: () -> Unit) {
        activeConfig = ConnectionConfig(
            host = ConnectionConfig.DEFAULT_HOST,
            apiPort = ConnectionConfig.DEFAULT_API_PORT,
            camPort = ConnectionConfig.DEFAULT_CAM_PORT,
            demoMode = true
        )
        onReady()
    }

    fun runPreflight(demoMode: Boolean, warning: String?, onComplete: () -> Unit) {
        preflightJob?.cancel()
        val cfg = activeConfig.copy(demoMode = demoMode)
        activeConfig = cfg

        preflightJob = viewModelScope.launch {
            if (demoMode) {
                _preflightState.value = PreflightState(
                    tick = 0,
                    apiOk = true,
                    lidarOk = true,
                    cameraOk = true,
                    statusDetail = "Demo simulator subsystems armed for preview...",
                    warningBanner = warning
                )
                for (t in 1..3) {
                    delay(1_000L)
                    _preflightState.update { it.copy(tick = t) }
                }
                onComplete()
                return@launch
            }

            _preflightState.value = PreflightState(
                tick = 0,
                statusDetail = "Probing Control API, LiDAR health, and MJPEG camera...",
                warningBanner = warning
            )

            var apiOk = false
            var lidarOk = false
            var cameraOk = false
            var failureReason: String? = null

            val telemetryProbe = launch {
                when (val res = apiClient.fetchTelemetry(cfg, AmrApiClient.TIMEOUT_PREFLIGHT_MS)) {
                    is ApiResult.Success -> {
                        apiOk = true
                        lidarOk = SafetyInterlock.isLidarHealthy(res.data.lidar)
                    }
                    is ApiResult.HttpError -> {
                        failureReason = "API HTTP ${res.httpCode} (${res.reason})"
                    }
                    is ApiResult.NetworkError -> {
                        failureReason = "API unreachable: ${res.message}"
                    }
                    is ApiResult.ValidationError -> {
                        failureReason = res.message
                    }
                }
            }

            val cameraProbe = launch {
                val camRes = apiClient.probeCameraStatus(cfg)
                cameraOk = camRes is ApiResult.Success
            }

            for (t in 1..3) {
                delay(1_000L)
                _preflightState.update {
                    it.copy(
                        tick = t,
                        apiOk = apiOk,
                        lidarOk = lidarOk,
                        cameraOk = cameraOk,
                        statusDetail = if (apiOk) {
                            "Control API online (${t}/3). Verifying LiDAR & camera..."
                        } else {
                            "Probing ${cfg.apiBaseUrl()} (${t}/3)..."
                        }
                    )
                }
            }

            telemetryProbe.join()
            cameraProbe.join()

            if (apiOk) {
                _preflightState.update {
                    it.copy(
                        tick = 3,
                        apiOk = true,
                        lidarOk = lidarOk,
                        cameraOk = cameraOk,
                        failed = false,
                        statusDetail = "Preflight complete. Launching AMR Cockpit..."
                    )
                }
                onComplete()
            } else {
                _preflightState.update {
                    it.copy(
                        tick = 3,
                        apiOk = false,
                        lidarOk = lidarOk,
                        cameraOk = cameraOk,
                        failed = true,
                        statusDetail = failureReason ?: "Preflight failed: Control API did not respond."
                    )
                }
            }
        }
    }

    fun cancelPreflight() {
        preflightJob?.cancel()
        preflightJob = null
    }

    fun startCockpitSession() {
        val persisted = settingsStore.load()
        stateMachine.configure(activeConfig, persisted.rpmMax, persisted.pidGains)
        stateMachine.start()
    }

    fun exitCockpitSession() {
        stateMachine.stop()
    }

    fun onDirectionPress(direction: DriveDirection) {
        viewModelScope.launch {
            stateMachine.onDirectionHoldStart(direction)
        }
    }

    fun onDirectionRelease() {
        viewModelScope.launch {
            stateMachine.onDirectionRelease()
        }
    }

    fun onArmClick() {
        viewModelScope.launch {
            stateMachine.requestArm()
        }
    }

    fun onDisarmClick() {
        viewModelScope.launch {
            stateMachine.requestDisarm()
        }
    }

    fun onStopClick() {
        viewModelScope.launch {
            stateMachine.requestStop()
        }
    }

    fun onClearFaultClick() {
        viewModelScope.launch {
            stateMachine.requestClearFault()
        }
    }

    fun onAdjustRpm(delta: Int) {
        viewModelScope.launch {
            val newRpm = stateMachine.adjustRpm(delta)
            settingsStore.saveRpm(newRpm)
        }
    }

    fun onOpenSettings() {
        viewModelScope.launch {
            stateMachine.onOpenSettingsRequested()
        }
    }

    fun onCloseSettings() {
        stateMachine.closeSettings()
    }

    fun onOpenMission() {
        viewModelScope.launch {
            stateMachine.onOpenMissionRequested()
        }
    }

    fun onCloseMission() {
        stateMachine.closeMission()
    }

    fun onApplySettings(
        host: String,
        apiPort: Int,
        camPort: Int,
        rpmMax: Int,
        gains: PidGains
    ) {
        viewModelScope.launch {
            val newCfg = activeConfig.copy(
                host = host,
                apiPort = apiPort,
                camPort = camPort
            )
            val cfgValidation = newCfg.validate()
            if (cfgValidation is ValidationResult.Invalid) {
                stateMachine.reportCommandNotice(cfgValidation.message)
                return@launch
            }
            if (!gains.isValid()) {
                stateMachine.reportCommandNotice("PID gains must be finite non-negative numbers.")
                return@launch
            }

            val clampedRpm = Kinematics.clampRpm(rpmMax)
            activeConfig = newCfg
            if (!newCfg.demoMode) {
                settingsStore.saveConnection(newCfg.normalizedHost, newCfg.apiPort, newCfg.camPort)
            }
            settingsStore.saveRpm(clampedRpm)
            settingsStore.savePid(gains)
            _connectionForm.update {
                it.copy(
                    host = newCfg.normalizedHost,
                    apiPortText = newCfg.apiPort.toString(),
                    camPortText = newCfg.camPort.toString()
                )
            }

            if (!newCfg.demoMode) {
                stateMachine.configure(newCfg, clampedRpm, gains)
                stateMachine.start()
                stateMachine.applyPid(gains)
                stateMachine.adjustRpm(0)
            } else {
                stateMachine.applyPid(gains)
                val delta = clampedRpm - cockpitState.value.desiredRpm
                if (delta != 0) {
                    stateMachine.adjustRpm(delta)
                }
            }
            stateMachine.closeSettings()
        }
    }

    fun onApplyPidOnly(gains: PidGains) {
        viewModelScope.launch {
            if (stateMachine.applyPid(gains)) {
                settingsStore.savePid(gains)
            }
        }
    }

    fun onResetMap() {
        viewModelScope.launch {
            stateMachine.resetMap()
        }
    }

    fun toggleDemoObstacle(direction: DriveDirection?) {
        stateMachine.injectDemoObstacle(direction, 32.0)
    }

    override fun onCleared() {
        preflightJob?.cancel()
        stateMachine.stop()
        super.onCleared()
    }
}
