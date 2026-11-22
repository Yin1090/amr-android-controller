package com.yin1090.amrcontroller.state

import com.yin1090.amrcontroller.demo.DemoSimulatorEngine
import com.yin1090.amrcontroller.domain.InterlockDecision
import com.yin1090.amrcontroller.domain.Kinematics
import com.yin1090.amrcontroller.domain.SafetyInterlock
import com.yin1090.amrcontroller.model.ArmState
import com.yin1090.amrcontroller.model.CockpitUiState
import com.yin1090.amrcontroller.model.ConnectionConfig
import com.yin1090.amrcontroller.model.ConnectionStatus
import com.yin1090.amrcontroller.model.DriveDirection
import com.yin1090.amrcontroller.model.PidGains
import com.yin1090.amrcontroller.model.SafetyZones
import com.yin1090.amrcontroller.model.TelemetrySnapshot
import com.yin1090.amrcontroller.net.AmrApiClient
import com.yin1090.amrcontroller.net.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CockpitStateMachine(
    private val scope: CoroutineScope,
    private val apiClient: AmrApiClient,
    private val clock: ClockProvider = SystemClockProvider(),
    private val demoEngine: DemoSimulatorEngine = DemoSimulatorEngine(),
    initialConfig: ConnectionConfig = ConnectionConfig(),
    initialRpm: Int = Kinematics.DEFAULT_RPM,
    initialPid: PidGains = PidGains()
) {
    private val stateMutex = Mutex()
    private val commandMutex = Mutex()

    private val _state = MutableStateFlow(
        CockpitUiState(
            config = initialConfig,
            demoMode = initialConfig.demoMode,
            connectionStatus = if (initialConfig.demoMode) ConnectionStatus.DEMO else ConnectionStatus.IDLE,
            desiredRpm = Kinematics.clampRpm(initialRpm),
            pidGains = initialPid
        )
    )
    val state: StateFlow<CockpitUiState> = _state.asStateFlow()

    private var telemetryPollJob: Job? = null
    private var mapPollJob: Job? = null
    private var watchdogJob: Job? = null
    private var holdLoopJob: Job? = null

    fun configure(config: ConnectionConfig, rpm: Int = _state.value.desiredRpm, pid: PidGains = _state.value.pidGains) {
        stopPollingOnly()
        val prev = _state.value
        if (!prev.demoMode && (prev.activeDirection != null || prev.armState == ArmState.ARMED)) {
            val stopEpoch = prev.epoch + 1L
            val prevConfig = prev.config
            val prevSeq = prev.seq
            scope.launch {
                sendStopWire(prevConfig, stopEpoch, prevSeq, alsoSendStopCmd = true)
            }
        }
        demoEngine.resetPoseAndMap()
        _state.value = CockpitUiState(
            config = config,
            demoMode = config.demoMode,
            connectionStatus = if (config.demoMode) ConnectionStatus.DEMO else ConnectionStatus.CONNECTING,
            desiredRpm = Kinematics.clampRpm(rpm),
            pidGains = pid,
            lidarOk = config.demoMode,
            dpadLocked = true
        )
    }

    fun start() {
        stopPollingOnly()
        val demo = _state.value.demoMode
        if (demo) {
            val now = clock.nowMs()
            val snap = demoEngine.step(0.0, now)
            val map = demoEngine.buildOccupancyMap(now)
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.DEMO,
                    lidarOk = true,
                    telemetryStale = false,
                    telemetryAgeBadgeStale = false,
                    mapStale = false,
                    lastTelemetryAtMs = now,
                    lastMapAtMs = now,
                    latestTelemetry = snap,
                    latestMap = map,
                    blockedDirections = SafetyInterlock.blockedDirections(snap.zones)
                )
            }
        }

        telemetryPollJob = scope.launch {
            while (isActive) {
                pollTelemetryOnce()
                delay(TELEMETRY_POLL_MS)
            }
        }
        mapPollJob = scope.launch {
            while (isActive) {
                pollMapOnce()
                delay(MAP_POLL_MS)
            }
        }
        if (!demo) {
            watchdogJob = scope.launch {
                while (isActive) {
                    delay(WATCHDOG_POLL_MS)
                    stepWatchdogOnce()
                }
            }
        }
    }

    fun stop() {
        stopPollingOnly()
        val cur = _state.value
        if (!cur.demoMode && (cur.activeDirection != null || cur.armState == ArmState.ARMED)) {
            val stopEpoch = cur.epoch + 1L
            val configSnapshot = cur.config
            val seqSnapshot = cur.seq
            _state.update {
                it.copy(
                    epoch = stopEpoch,
                    activeDirection = null,
                    commandedV = 0.0,
                    commandedW = 0.0,
                    armState = ArmState.DISARMED,
                    dpadLocked = true
                )
            }
            scope.launch {
                sendStopWire(configSnapshot, stopEpoch, seqSnapshot, alsoSendStopCmd = true)
            }
        }
    }

    private fun stopPollingOnly() {
        holdLoopJob?.cancel()
        holdLoopJob = null
        telemetryPollJob?.cancel()
        telemetryPollJob = null
        mapPollJob?.cancel()
        mapPollJob = null
        watchdogJob?.cancel()
        watchdogJob = null
    }

    internal suspend fun pollTelemetryOnce() {
        val cur = _state.value
        val now = clock.nowMs()
        if (cur.demoMode) {
            val snap = demoEngine.step(TELEMETRY_POLL_MS / 1000.0, now)
            applyTelemetrySnapshot(snap, now)
            return
        }

        when (val result = apiClient.fetchTelemetry(cur.config, AmrApiClient.TIMEOUT_TELEMETRY_COCKPIT_MS, now)) {
            is ApiResult.Success -> {
                applyTelemetrySnapshot(result.data, now)
            }
            is ApiResult.HttpError -> {
                _state.update {
                    it.copy(
                        statusMessage = "Telemetry error: HTTP ${result.httpCode} (${result.reason})"
                    )
                }
            }
            is ApiResult.NetworkError -> {
                _state.update {
                    it.copy(
                        statusMessage = "Telemetry offline: ${result.message}"
                    )
                }
            }
            is ApiResult.ValidationError -> {
                _state.update {
                    it.copy(statusMessage = result.message)
                }
            }
        }
    }

    private suspend fun applyTelemetrySnapshot(snap: TelemetrySnapshot, nowMs: Long) {
        var shouldAutoHaltMessage: String? = null
        stateMutex.withLock {
            val cur = _state.value
            val lidarHealthy = if (cur.demoMode) true else SafetyInterlock.isLidarHealthy(snap.lidar)
            val newArmState = when {
                !snap.control.fault.isNullOrBlank() -> ArmState.FAULT
                snap.control.armed -> ArmState.ARMED
                else -> ArmState.DISARMED
            }
            val blocked = SafetyInterlock.blockedDirections(snap.zones)
            val mapIsStale = if (cur.demoMode) false else SafetyInterlock.isMapStale(cur.lastMapAtMs, nowMs)
            val dpadLocked = newArmState != ArmState.ARMED || (!cur.demoMode && !lidarHealthy) || cur.stopPending

            val activeDir = cur.activeDirection
            if (activeDir != null) {
                val decision = SafetyInterlock.evaluateDrive(
                    direction = activeDir,
                    armState = newArmState,
                    stopPending = cur.stopPending,
                    demoMode = cur.demoMode,
                    lidarOk = lidarHealthy,
                    telemetryStale = false,
                    zones = snap.zones
                )
                if (decision is InterlockDecision.Blocked) {
                    shouldAutoHaltMessage = decision.userMessage
                }
            }

            _state.update {
                it.copy(
                    connectionStatus = if (cur.demoMode) ConnectionStatus.DEMO else ConnectionStatus.CONNECTED,
                    armState = newArmState,
                    lidarOk = lidarHealthy,
                    telemetryStale = false,
                    telemetryAgeBadgeStale = false,
                    mapStale = mapIsStale,
                    dpadLocked = dpadLocked,
                    lastTelemetryAtMs = nowMs,
                    latestTelemetry = snap,
                    blockedDirections = blocked
                )
            }
        }

        if (shouldAutoHaltMessage != null) {
            autoHalt(shouldAutoHaltMessage!!)
        }
    }

    internal suspend fun pollMapOnce() {
        val cur = _state.value
        val now = clock.nowMs()
        if (cur.demoMode) {
            val demoMap = demoEngine.buildOccupancyMap(now)
            _state.update {
                it.copy(
                    latestMap = demoMap,
                    lastMapAtMs = now,
                    mapStale = false
                )
            }
            return
        }

        when (val result = apiClient.fetchFullMap(cur.config, now)) {
            is ApiResult.Success -> {
                if (result.data.isValid()) {
                    _state.update {
                        it.copy(
                            latestMap = result.data,
                            lastMapAtMs = now,
                            mapStale = false
                        )
                    }
                }
            }
            else -> {
                // Keep last valid map on failure; update staleness flag
                _state.update {
                    it.copy(mapStale = SafetyInterlock.isMapStale(it.lastMapAtMs, now))
                }
            }
        }
    }

    internal suspend fun stepWatchdogOnce() {
        val cur = _state.value
        if (cur.demoMode) return
        val now = clock.nowMs()
        val expired = SafetyInterlock.isTelemetryWatchdogExpired(cur.lastTelemetryAtMs, now)
        val badgeStale = SafetyInterlock.isTelemetryBadgeStale(cur.lastTelemetryAtMs, now)
        val mapStale = SafetyInterlock.isMapStale(cur.lastMapAtMs, now)

        if (expired && cur.lastTelemetryAtMs > 0L) {
            val hadActiveDrive = cur.activeDirection != null || cur.armState == ArmState.ARMED
            if (hadActiveDrive) {
                holdLoopJob?.cancel()
                holdLoopJob = null
                val nextEpoch = stateMutex.withLock {
                    val e = _state.value.epoch + 1L
                    _state.update {
                        it.copy(
                            epoch = e,
                            activeDirection = null,
                            commandedV = 0.0,
                            commandedW = 0.0,
                            armState = ArmState.DISARMED,
                            telemetryStale = true,
                            telemetryAgeBadgeStale = badgeStale,
                            mapStale = mapStale,
                            lidarOk = false,
                            dpadLocked = true,
                            connectionStatus = ConnectionStatus.STALE,
                            interlockWarning = "Telemetry stale (>1.5s) — drive locked & stopped"
                        )
                    }
                    e
                }
                val seq = _state.value.seq
                sendStopWire(cur.config, nextEpoch, seq, alsoSendStopCmd = true)
            } else {
                _state.update {
                    it.copy(
                        telemetryStale = true,
                        telemetryAgeBadgeStale = badgeStale,
                        mapStale = mapStale,
                        lidarOk = false,
                        dpadLocked = true,
                        connectionStatus = ConnectionStatus.STALE
                    )
                }
            }
        } else {
            _state.update {
                it.copy(
                    telemetryAgeBadgeStale = badgeStale,
                    mapStale = mapStale
                )
            }
        }
    }

    suspend fun onDirectionHoldStart(direction: DriveDirection) {
        var holdEpoch = 0L
        var blockedEpoch = 0L
        var twistV = 0.0
        var twistW = 0.0
        var isDemo = false
        var armStateCopy = ArmState.DISARMED
        var configCopy: ConnectionConfig? = null
        var seqCopy = 0L

        val allowed = stateMutex.withLock {
            val cur = _state.value
            isDemo = cur.demoMode
            armStateCopy = cur.armState
            configCopy = cur.config
            seqCopy = cur.seq
            blockedEpoch = cur.epoch
            val effectiveStale = if (cur.demoMode) {
                false
            } else {
                cur.telemetryStale || SafetyInterlock.isTelemetryWatchdogExpired(cur.lastTelemetryAtMs, clock.nowMs())
            }
            val zones = cur.latestTelemetry?.zones ?: SafetyZones.ALL_BLOCKED
            val decision = SafetyInterlock.evaluateDrive(
                direction = direction,
                armState = cur.armState,
                stopPending = cur.stopPending,
                demoMode = cur.demoMode,
                lidarOk = cur.lidarOk,
                telemetryStale = effectiveStale,
                zones = zones
            )

            if (decision is InterlockDecision.Blocked) {
                holdLoopJob?.cancel()
                holdLoopJob = null
                _state.update {
                    it.copy(
                        activeDirection = null,
                        commandedV = 0.0,
                        commandedW = 0.0,
                        interlockWarning = decision.userMessage
                    )
                }
                false
            } else {
                val twist = Kinematics.computeTwist(cur.desiredRpm, direction)
                val nextEpoch = cur.epoch + 1L
                holdEpoch = nextEpoch
                twistV = twist.linearMps
                twistW = twist.angularRadPerSec
                _state.update {
                    it.copy(
                        epoch = nextEpoch,
                        activeDirection = direction,
                        commandedV = twistV,
                        commandedW = twistW,
                        interlockWarning = null
                    )
                }
                holdLoopJob?.cancel()
                if (isDemo) {
                    demoEngine.setTargetTwist(twistV, twistW)
                }
                holdLoopJob = scope.launch {
                    while (isActive) {
                        delay(HOLD_REPEAT_MS)
                        val tickState = _state.value
                        if (tickState.epoch != holdEpoch || tickState.activeDirection != direction || tickState.stopPending) {
                            break
                        }
                        val tickStale = if (tickState.demoMode) {
                            false
                        } else {
                            tickState.telemetryStale ||
                                SafetyInterlock.isTelemetryWatchdogExpired(tickState.lastTelemetryAtMs, clock.nowMs())
                        }
                        val tickZones = tickState.latestTelemetry?.zones ?: SafetyZones.ALL_BLOCKED
                        val recheck = SafetyInterlock.evaluateDrive(
                            direction = direction,
                            armState = tickState.armState,
                            stopPending = tickState.stopPending,
                            demoMode = tickState.demoMode,
                            lidarOk = tickState.lidarOk,
                            telemetryStale = tickStale,
                            zones = tickZones
                        )
                        if (recheck is InterlockDecision.Blocked) {
                            autoHalt(recheck.userMessage)
                            break
                        }
                        if (tickState.demoMode) {
                            demoEngine.setTargetTwist(twistV, twistW)
                        } else {
                            dispatchHoldTick(tickState.config, direction, twistV, twistW, holdEpoch, tickState.seq)
                        }
                    }
                }
                true
            }
        }

        if (!allowed) {
            if (!isDemo && armStateCopy != ArmState.ARMED && configCopy != null) {
                sendStopWire(configCopy!!, blockedEpoch, seqCopy)
            }
            return
        }

        if (!isDemo && configCopy != null) {
            dispatchHoldTick(configCopy!!, direction, twistV, twistW, holdEpoch, seqCopy)
        }
    }

    private suspend fun dispatchHoldTick(
        config: ConnectionConfig,
        direction: DriveDirection,
        v: Double,
        w: Double,
        expectedEpoch: Long,
        seqSnapshot: Long
    ) {
        commandMutex.withLock {
            val curBefore = _state.value
            if (curBefore.epoch > expectedEpoch ||
                curBefore.epoch != expectedEpoch ||
                curBefore.activeDirection != direction ||
                curBefore.stopPending
            ) {
                return
            }
            val cmdStr = Kinematics.formatVelocityCommand(v, w)
            val result = apiClient.sendCommand(config, cmdStr, expectedEpoch, seqSnapshot)
            if (result is ApiResult.Success && result.data.isSent) {
                _state.update { state ->
                    if (state.epoch == expectedEpoch) {
                        state.copy(seq = state.seq + 1L)
                    } else {
                        state
                    }
                }
            }
        }
    }

    suspend fun onDirectionRelease() {
        var stopEpoch = 0L
        var seqCopy = 0L
        var isDemo = false
        var configCopy: ConnectionConfig? = null

        stateMutex.withLock {
            holdLoopJob?.cancel()
            holdLoopJob = null
            val cur = _state.value
            val nextEpoch = cur.epoch + 1L
            stopEpoch = nextEpoch
            seqCopy = cur.seq
            isDemo = cur.demoMode
            configCopy = cur.config
            _state.update {
                it.copy(
                    epoch = nextEpoch,
                    activeDirection = null,
                    commandedV = 0.0,
                    commandedW = 0.0
                )
            }
            if (isDemo) {
                demoEngine.setTargetTwist(0.0, 0.0)
            }
        }

        if (!isDemo && configCopy != null) {
            sendStopWire(configCopy!!, stopEpoch, seqCopy)
        }
    }

    suspend fun autoHalt(warning: String) {
        var stopEpoch = 0L
        var seqCopy = 0L
        var isDemo = false
        var configCopy: ConnectionConfig? = null

        stateMutex.withLock {
            holdLoopJob?.cancel()
            holdLoopJob = null
            val cur = _state.value
            val nextEpoch = cur.epoch + 1L
            stopEpoch = nextEpoch
            seqCopy = cur.seq
            isDemo = cur.demoMode
            configCopy = cur.config
            _state.update {
                it.copy(
                    epoch = nextEpoch,
                    activeDirection = null,
                    commandedV = 0.0,
                    commandedW = 0.0,
                    interlockWarning = warning
                )
            }
            if (isDemo) {
                demoEngine.setTargetTwist(0.0, 0.0)
            }
        }

        if (!isDemo && configCopy != null) {
            sendStopWire(configCopy!!, stopEpoch, seqCopy)
        }
    }

    private suspend fun sendStopWire(
        config: ConnectionConfig,
        stopEpoch: Long,
        seq: Long,
        alsoSendStopCmd: Boolean = false,
        alsoSendDisarmCmd: Boolean = false
    ) {
        withContext(NonCancellable) {
            commandMutex.withLock {
                apiClient.sendCommand(config, Kinematics.STOP_COMMAND, stopEpoch, seq)
                if (alsoSendStopCmd) {
                    apiClient.sendCommand(config, "STOP", stopEpoch, seq)
                }
                if (alsoSendDisarmCmd) {
                    apiClient.sendCommand(config, "DISARM", stopEpoch, seq)
                }
            }
        }
    }

    suspend fun requestArm() {
        val cur = _state.value
        if (cur.demoMode) {
            demoEngine.setFault(null)
            demoEngine.setArmed(true)
            _state.update {
                it.copy(
                    armState = ArmState.ARMED,
                    dpadLocked = false,
                    interlockWarning = null,
                    lastCommandNotice = "DEMO ARMED"
                )
            }
            return
        }

        val result = apiClient.sendCommand(cur.config, "ARM", cur.epoch, cur.seq)
        when (result) {
            is ApiResult.Success -> {
                val cmd = result.data
                if (cmd.isSent) {
                    _state.update {
                        it.copy(
                            armState = ArmState.ARMED,
                            dpadLocked = !it.lidarOk || it.telemetryStale,
                            interlockWarning = null,
                            lastCommandNotice = "ARMED"
                        )
                    }
                } else {
                    val activeFault = cur.latestTelemetry?.control?.fault
                    val faultBlocker = cmd.blockers.any { it.contains("FAULT", ignoreCase = true) } ||
                        (cmd.reason?.contains("FAULT", ignoreCase = true) == true)
                    if (!activeFault.isNullOrBlank() || faultBlocker) {
                        apiClient.sendCommand(cur.config, "CLEAR_FAULT", cur.epoch, cur.seq)
                        val label = activeFault ?: cmd.reason ?: "FAULT"
                        _state.update {
                            it.copy(
                                armState = ArmState.FAULT,
                                dpadLocked = true,
                                lastCommandNotice = "Fault ($label): CLEAR_FAULT sent. Press ARM again in 1s."
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                lastCommandNotice = "ARM rejected: ${cmd.reason ?: "HTTP ${cmd.httpCode}"}"
                            )
                        }
                    }
                }
            }
            is ApiResult.HttpError -> {
                _state.update {
                    it.copy(lastCommandNotice = "ARM failed: HTTP ${result.httpCode} (${result.reason})")
                }
            }
            is ApiResult.NetworkError -> {
                _state.update {
                    it.copy(lastCommandNotice = "ARM network error: ${result.message}")
                }
            }
            is ApiResult.ValidationError -> {
                _state.update {
                    it.copy(lastCommandNotice = result.message)
                }
            }
        }
    }

    suspend fun requestDisarm() {
        var stopEpoch = 0L
        var seqCopy = 0L
        var isDemo = false
        var configCopy: ConnectionConfig? = null

        stateMutex.withLock {
            holdLoopJob?.cancel()
            holdLoopJob = null
            val cur = _state.value
            stopEpoch = cur.epoch + 1L
            seqCopy = cur.seq
            isDemo = cur.demoMode
            configCopy = cur.config
            _state.update {
                it.copy(
                    epoch = stopEpoch,
                    activeDirection = null,
                    commandedV = 0.0,
                    commandedW = 0.0,
                    armState = ArmState.DISARMED,
                    dpadLocked = true
                )
            }
            if (isDemo) {
                demoEngine.setTargetTwist(0.0, 0.0)
                demoEngine.setArmed(false)
            }
        }

        if (isDemo) {
            _state.update { it.copy(lastCommandNotice = "DEMO DISARMED") }
        } else if (configCopy != null) {
            sendStopWire(configCopy!!, stopEpoch, seqCopy, alsoSendDisarmCmd = true)
            _state.update { it.copy(lastCommandNotice = "DISARMED") }
        }
    }

    suspend fun requestStop() {
        var stopEpoch = 0L
        var seqCopy = 0L
        var isDemo = false
        var configCopy: ConnectionConfig? = null

        stateMutex.withLock {
            holdLoopJob?.cancel()
            holdLoopJob = null
            val cur = _state.value
            stopEpoch = cur.epoch + 1L
            seqCopy = cur.seq
            isDemo = cur.demoMode
            configCopy = cur.config
            _state.update {
                it.copy(
                    stopPending = true,
                    epoch = stopEpoch,
                    activeDirection = null,
                    commandedV = 0.0,
                    commandedW = 0.0,
                    dpadLocked = true
                )
            }
            if (isDemo) {
                demoEngine.setTargetTwist(0.0, 0.0)
                demoEngine.setArmed(false)
            }
        }

        try {
            if (isDemo) {
                _state.update {
                    it.copy(
                        armState = ArmState.DISARMED,
                        lastCommandNotice = "DEMO EMERGENCY STOP"
                    )
                }
            } else if (configCopy != null) {
                sendStopWire(configCopy!!, stopEpoch, seqCopy, alsoSendStopCmd = true)
                _state.update {
                    it.copy(
                        armState = ArmState.DISARMED,
                        lastCommandNotice = "EMERGENCY STOP SENT"
                    )
                }
            }
        } finally {
            _state.update { it.copy(stopPending = false) }
        }
    }

    suspend fun requestClearFault() {
        val cur = _state.value
        if (cur.demoMode) {
            demoEngine.setFault(null)
            _state.update {
                it.copy(
                    armState = ArmState.DISARMED,
                    lastCommandNotice = "DEMO FAULT CLEARED"
                )
            }
            return
        }
        apiClient.sendCommand(cur.config, "CLEAR_FAULT", cur.epoch, cur.seq)
        _state.update { it.copy(lastCommandNotice = "CLEAR_FAULT sent") }
    }

    suspend fun adjustRpm(delta: Int): Int {
        val newRpm = Kinematics.adjustRpm(_state.value.desiredRpm, delta)
        _state.update { it.copy(desiredRpm = newRpm) }

        val cur = _state.value
        if (!cur.demoMode) {
            when (val res = apiClient.setAutoConfig(cur.config, newRpm, newRpm)) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            effectiveRpm = newRpm,
                            lastCommandNotice = "Target speed set to $newRpm RPM"
                        )
                    }
                }
                is ApiResult.HttpError -> {
                    _state.update {
                        it.copy(
                            lastCommandNotice = "Manual RPM $newRpm (backend auto_config HTTP ${res.httpCode}: ${res.reason})"
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    _state.update {
                        it.copy(lastCommandNotice = "Manual RPM $newRpm (auto_config offline)")
                    }
                }
                is ApiResult.ValidationError -> {
                    _state.update { it.copy(lastCommandNotice = res.message) }
                }
            }
        } else {
            _state.update {
                it.copy(
                    effectiveRpm = newRpm,
                    lastCommandNotice = "Demo target speed: $newRpm RPM"
                )
            }
        }
        return newRpm
    }

    suspend fun applyPid(gains: PidGains): Boolean {
        if (!gains.isValid()) {
            _state.update { it.copy(lastCommandNotice = "Invalid PID gains") }
            return false
        }
        _state.update { it.copy(pidGains = gains) }
        val cur = _state.value
        if (cur.demoMode) {
            _state.update { it.copy(lastCommandNotice = "Demo PID updated") }
            return true
        }
        return when (val res = apiClient.applyPid(cur.config, gains)) {
            is ApiResult.Success -> {
                _state.update { it.copy(lastCommandNotice = "PID gains applied") }
                true
            }
            is ApiResult.HttpError -> {
                _state.update { it.copy(lastCommandNotice = "PID rejected: HTTP ${res.httpCode}") }
                false
            }
            is ApiResult.NetworkError -> {
                _state.update { it.copy(lastCommandNotice = "PID network error: ${res.message}") }
                false
            }
            is ApiResult.ValidationError -> {
                _state.update { it.copy(lastCommandNotice = res.message) }
                false
            }
        }
    }

    suspend fun resetMap(): Boolean {
        val cur = _state.value
        if (cur.demoMode) {
            demoEngine.resetPoseAndMap()
            val now = clock.nowMs()
            _state.update {
                it.copy(
                    latestMap = demoEngine.buildOccupancyMap(now),
                    lastMapAtMs = now,
                    lastCommandNotice = "Demo map reset"
                )
            }
            return true
        }
        return when (val res = apiClient.resetMap(cur.config)) {
            is ApiResult.Success -> {
                _state.update { it.copy(lastCommandNotice = "SLAM map reset requested") }
                true
            }
            is ApiResult.HttpError -> {
                _state.update { it.copy(lastCommandNotice = "Map reset failed: HTTP ${res.httpCode}") }
                false
            }
            is ApiResult.NetworkError -> {
                _state.update { it.copy(lastCommandNotice = "Map reset offline: ${res.message}") }
                false
            }
            is ApiResult.ValidationError -> {
                _state.update { it.copy(lastCommandNotice = res.message) }
                false
            }
        }
    }

    suspend fun onOpenSettingsRequested(): Boolean {
        val cur = _state.value
        if (cur.armState == ArmState.ARMED) {
            requestStop()
            _state.update {
                it.copy(
                    settingsOpen = false,
                    interlockWarning = "Stop vehicle before opening settings"
                )
            }
            return false
        }
        _state.update { it.copy(settingsOpen = true, missionOpen = false) }
        return true
    }

    fun closeSettings() {
        _state.update { it.copy(settingsOpen = false) }
    }

    fun reportCommandNotice(message: String) {
        _state.update { it.copy(lastCommandNotice = message, statusMessage = message) }
    }

    suspend fun onOpenMissionRequested(): Boolean {
        val cur = _state.value
        if (cur.demoMode) {
            _state.update {
                it.copy(
                    missionOpen = false,
                    interlockWarning = "Mission web dashboard disabled in Offline Demo mode"
                )
            }
            return false
        }
        if (cur.armState == ArmState.ARMED) {
            requestStop()
        }
        _state.update { it.copy(missionOpen = true, settingsOpen = false) }
        return true
    }

    fun closeMission() {
        _state.update { it.copy(missionOpen = false) }
    }

    fun injectDemoObstacle(direction: DriveDirection?, distanceCm: Double = 30.0) {
        if (!_state.value.demoMode) return
        if (direction == null) {
            demoEngine.clearZoneOverrides()
            demoEngine.clearVirtualObstacle()
        } else {
            demoEngine.setZoneOverride(direction, distanceCm)
            if (direction == DriveDirection.F) {
                demoEngine.placeVirtualObstacle(0.30, 0.0, 0.10)
            }
        }
    }

    companion object {
        const val TELEMETRY_POLL_MS = 400L
        const val MAP_POLL_MS = 1_000L
        const val WATCHDOG_POLL_MS = 250L
        const val HOLD_REPEAT_MS = 150L
    }
}
