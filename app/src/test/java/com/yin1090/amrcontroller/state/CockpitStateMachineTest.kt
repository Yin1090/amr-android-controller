package com.yin1090.amrcontroller.state

import com.yin1090.amrcontroller.demo.DemoSimulatorEngine
import com.yin1090.amrcontroller.model.ArmState
import com.yin1090.amrcontroller.model.ConnectionConfig
import com.yin1090.amrcontroller.model.ConnectionStatus
import com.yin1090.amrcontroller.model.DriveDirection
import com.yin1090.amrcontroller.net.AmrApiClient
import com.yin1090.amrcontroller.net.HttpResponse
import com.yin1090.amrcontroller.net.HttpTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private class FakeClock(var currentMs: Long = 1_000L) : ClockProvider {
    override fun nowMs(): Long = currentMs
}

private class FakeHttpTransport : HttpTransport {
    val requests = mutableListOf<String>()
    var telemetryJson: String = DEFAULT_TELEMETRY
    var armResponseCode: Int = 200
    var armResponseBody: String = """{"status":"SENT"}"""

    override suspend fun get(url: String, timeoutMs: Int, headers: Map<String, String>): HttpResponse {
        requests.add(url)
        return when {
            url.contains("/api/telemetry") -> HttpResponse(200, telemetryJson)
            url.contains("/api/map_full") -> HttpResponse(200, """{"w":2,"h":2,"res":0.05,"ox":0.0,"oy":0.0,"yaw":0.0,"data":[0,0,0,100]}""")
            url.contains("/api/cmd") -> {
                val cmdVal = extractCmdVal(url)
                if (cmdVal == "ARM") {
                    HttpResponse(armResponseCode, armResponseBody)
                } else {
                    HttpResponse(200, """{"status":"SENT"}""")
                }
            }
            else -> HttpResponse(200, """{"status":"OK"}""")
        }
    }

    fun sentCommands(): List<String> = requests
        .filter { it.contains("/api/cmd") }
        .map { extractCmdVal(it) }

    private fun extractCmdVal(url: String): String {
        val query = url.substringAfter("?", "")
        for (part in query.split("&")) {
            if (part.startsWith("val=")) {
                return URLDecoder.decode(part.removePrefix("val="), StandardCharsets.UTF_8.name())
            }
        }
        return ""
    }

    companion object {
        const val DEFAULT_TELEMETRY = """
            {
              "status": "SAFE",
              "min_m": 1.25,
              "min_ang": 0.0,
              "robot": {"x": 0.0, "y": 0.0, "th": 0.0, "l_rpm": 0.0, "r_rpm": 0.0},
              "control": {"armed": true, "fault": null},
              "lidar": {"connected": true, "age_s": 0.05},
              "ranges": [1.25],
              "map_info": {"robot_pose": [0.0, 0.0, 0.0]},
              "zones": {
                "front": 120.0, "back": 150.0, "left": 140.0, "right": 140.0,
                "front_left": 130.0, "front_right": 130.0, "back_left": 150.0, "back_right": 150.0
              }
            }
        """
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CockpitStateMachineTest {

    private fun liveConfig() = ConnectionConfig(host = "127.0.0.1", apiPort = 8080, camPort = 8081, demoMode = false)

    @Test
    fun initialStateIsDisarmedWithDefault40Rpm() = runTest {
        val transport = FakeHttpTransport()
        val sm = CockpitStateMachine(backgroundScope, AmrApiClient(transport), FakeClock(), DemoSimulatorEngine(), liveConfig())
        val s = sm.state.value
        assertEquals(ArmState.DISARMED, s.armState)
        assertEquals(40, s.desiredRpm)
        assertNull(s.activeDirection)
        assertEquals(0L, s.seq)
        assertTrue(s.epoch >= 1L)
    }

    @Test
    fun armTransitionAndFaultRecoverySendsClearFault() = runTest {
        val transport = FakeHttpTransport()
        val sm = CockpitStateMachine(backgroundScope, AmrApiClient(transport), FakeClock(), DemoSimulatorEngine(), liveConfig())

        sm.requestArm()
        assertEquals(ArmState.ARMED, sm.state.value.armState)

        transport.armResponseCode = 422
        transport.armResponseBody = """{"status":"REJECTED","reason":"FAULT_ACTIVE","blockers":["FAULT"]}"""
        sm.requestArm()
        assertEquals(ArmState.FAULT, sm.state.value.armState)
        assertTrue(transport.sentCommands().contains("CLEAR_FAULT"))
        assertTrue(sm.state.value.lastCommandNotice?.contains("CLEAR_FAULT") == true)
    }

    @Test
    fun disarmAndStopTransitionBackToDisarmed() = runTest {
        val transport = FakeHttpTransport()
        val sm = CockpitStateMachine(backgroundScope, AmrApiClient(transport), FakeClock(), DemoSimulatorEngine(), liveConfig())
        sm.requestArm()
        sm.requestDisarm()
        assertEquals(ArmState.DISARMED, sm.state.value.armState)
        assertTrue(transport.sentCommands().contains("DISARM"))
        assertTrue(transport.sentCommands().contains("V 0.000 0.000"))

        sm.requestArm()
        sm.requestStop()
        assertEquals(ArmState.DISARMED, sm.state.value.armState)
        assertTrue(transport.sentCommands().contains("STOP"))
    }

    @Test
    fun holdToDriveRepeatsAt150MsAndIncrementsSeq() = runTest {
        val transport = FakeHttpTransport()
        val clock = FakeClock(1_000L)
        val sm = CockpitStateMachine(backgroundScope, AmrApiClient(transport), clock, DemoSimulatorEngine(), liveConfig())
        sm.pollTelemetryOnce()

        sm.onDirectionHoldStart(DriveDirection.F)
        runCurrent()
        assertEquals(DriveDirection.F, sm.state.value.activeDirection)
        assertEquals(1L, sm.state.value.seq)

        advanceTimeBy(150L)
        runCurrent()
        assertEquals(2L, sm.state.value.seq)

        advanceTimeBy(150L)
        runCurrent()
        assertEquals(3L, sm.state.value.seq)

        val driveCmds = transport.sentCommands().filter { it == "V 0.153 0.000" }
        assertTrue(driveCmds.size >= 3)
        sm.onDirectionRelease()
    }

    @Test
    fun releaseToStopImmediatelyHaltsAndSendsZeroVelocity() = runTest {
        val transport = FakeHttpTransport()
        val sm = CockpitStateMachine(backgroundScope, AmrApiClient(transport), FakeClock(), DemoSimulatorEngine(), liveConfig())
        sm.pollTelemetryOnce()

        sm.onDirectionHoldStart(DriveDirection.F)
        runCurrent()
        assertEquals(DriveDirection.F, sm.state.value.activeDirection)

        sm.onDirectionRelease()
        assertNull(sm.state.value.activeDirection)
        assertEquals(0.0, sm.state.value.commandedV, 1e-9)
        assertEquals("V 0.000 0.000", transport.sentCommands().last())
    }

    @Test
    fun touchDownWhileDisarmedRefusesDrive() = runTest {
        val transport = FakeHttpTransport()
        val sm = CockpitStateMachine(backgroundScope, AmrApiClient(transport), FakeClock(), DemoSimulatorEngine(), liveConfig())
        sm.onDirectionHoldStart(DriveDirection.F)
        assertNull(sm.state.value.activeDirection)
        assertFalse(transport.sentCommands().any { it.startsWith("V 0.153") })
        assertTrue(sm.state.value.interlockWarning?.contains("DISARMED") == true)
    }

    @Test
    fun midMotionObstacleUnder40CmTriggersAutoHalt() = runTest {
        val transport = FakeHttpTransport()
        val clock = FakeClock(2_000L)
        val sm = CockpitStateMachine(backgroundScope, AmrApiClient(transport), clock, DemoSimulatorEngine(), liveConfig())
        sm.pollTelemetryOnce()
        sm.onDirectionHoldStart(DriveDirection.F)
        runCurrent()
        assertEquals(DriveDirection.F, sm.state.value.activeDirection)

        transport.telemetryJson = FakeHttpTransport.DEFAULT_TELEMETRY.replace("\"front\": 120.0", "\"front\": 35.0")
        clock.currentMs += 400L
        sm.pollTelemetryOnce()

        assertNull(sm.state.value.activeDirection)
        assertEquals("V 0.000 0.000", transport.sentCommands().last())
        assertTrue(sm.state.value.interlockWarning?.contains("FRONT") == true)
    }

    @Test
    fun settingsAndMissionBlockedOrAutoStoppedWhenArmed() = runTest {
        val transport = FakeHttpTransport()
        val sm = CockpitStateMachine(backgroundScope, AmrApiClient(transport), FakeClock(), DemoSimulatorEngine(), liveConfig())
        sm.requestArm()

        val settingsOpened = sm.onOpenSettingsRequested()
        assertFalse(settingsOpened)
        assertFalse(sm.state.value.settingsOpen)
        assertEquals(ArmState.DISARMED, sm.state.value.armState)
        assertTrue(transport.sentCommands().contains("STOP"))

        sm.requestArm()
        val missionOpened = sm.onOpenMissionRequested()
        assertTrue(missionOpened)
        assertTrue(sm.state.value.missionOpen)
        assertEquals(ArmState.DISARMED, sm.state.value.armState)
    }

    @Test
    fun watchdogExpiryWhileArmedDisarmsAndHalts() = runTest {
        val transport = FakeHttpTransport()
        val clock = FakeClock(1_000L)
        val sm = CockpitStateMachine(backgroundScope, AmrApiClient(transport), clock, DemoSimulatorEngine(), liveConfig())
        sm.pollTelemetryOnce()
        sm.onDirectionHoldStart(DriveDirection.F)
        runCurrent()

        clock.currentMs += 1_501L
        sm.stepWatchdogOnce()

        assertEquals(ArmState.DISARMED, sm.state.value.armState)
        assertEquals(ConnectionStatus.STALE, sm.state.value.connectionStatus)
        assertTrue(sm.state.value.telemetryStale)
        assertTrue(sm.state.value.dpadLocked)
        assertNull(sm.state.value.activeDirection)
        assertTrue(transport.sentCommands().contains("V 0.000 0.000"))
        assertTrue(transport.sentCommands().contains("STOP"))
    }

    @Test
    fun demoModeSupportsSimulatedDriveObstacleAutoHaltAndBlocksMission() = runTest {
        val demoConfig = ConnectionConfig(demoMode = true)
        val clock = FakeClock(5_000L)
        val sm = CockpitStateMachine(backgroundScope, AmrApiClient(FakeHttpTransport()), clock, DemoSimulatorEngine(), demoConfig)
        sm.pollTelemetryOnce()

        sm.requestArm()
        assertEquals(ArmState.ARMED, sm.state.value.armState)
        assertFalse(sm.onOpenMissionRequested())

        sm.onDirectionHoldStart(DriveDirection.F)
        runCurrent()
        assertEquals(DriveDirection.F, sm.state.value.activeDirection)

        sm.injectDemoObstacle(DriveDirection.F, 32.0)
        clock.currentMs += 400L
        sm.pollTelemetryOnce()

        assertNull(sm.state.value.activeDirection)
        assertTrue(sm.state.value.interlockWarning?.contains("FRONT") == true)
    }

    @Test
    fun holdLoopJob150MsTickBlockedAutoHaltTransmitsStopCommand() = runTest {
        val transport = FakeHttpTransport()
        val clock = FakeClock(1_000L)
        val sm = CockpitStateMachine(backgroundScope, AmrApiClient(transport), clock, DemoSimulatorEngine(), liveConfig())
        sm.pollTelemetryOnce()
        sm.onDirectionHoldStart(DriveDirection.F)
        runCurrent()
        assertEquals(DriveDirection.F, sm.state.value.activeDirection)
        assertEquals("V 0.153 0.000", transport.sentCommands().last())

        // Advance clock past 1500 ms watchdog without calling stepWatchdogOnce()
        // so the 150 ms holdLoopJob tick itself hits Blocked -> autoHalt()
        clock.currentMs += 1_501L
        advanceTimeBy(150L)
        runCurrent()

        assertNull(sm.state.value.activeDirection)
        assertEquals("V 0.000 0.000", transport.sentCommands().last())
        assertTrue(sm.state.value.interlockWarning?.contains("telemetry stale", ignoreCase = true) == true)
    }

    @Test
    fun offlineDemoDisarmedDpadPressIssuesZeroHttpRequests() = runTest {
        val transport = FakeHttpTransport()
        val demoConfig = ConnectionConfig(demoMode = true)
        val sm = CockpitStateMachine(backgroundScope, AmrApiClient(transport), FakeClock(), DemoSimulatorEngine(), demoConfig)
        assertEquals(ArmState.DISARMED, sm.state.value.armState)

        sm.onDirectionHoldStart(DriveDirection.F)
        runCurrent()

        assertNull(sm.state.value.activeDirection)
        assertTrue(transport.requests.isEmpty())
        assertTrue(sm.state.value.interlockWarning?.contains("DISARMED") == true)
    }

    @Test
    fun holdStartAfter1501MsWithoutWatchdogTickBlocksImmediately() = runTest {
        val transport = FakeHttpTransport()
        val clock = FakeClock(1_000L)
        val sm = CockpitStateMachine(backgroundScope, AmrApiClient(transport), clock, DemoSimulatorEngine(), liveConfig())
        sm.pollTelemetryOnce()

        clock.currentMs += 1_501L
        sm.onDirectionHoldStart(DriveDirection.F)
        runCurrent()

        assertNull(sm.state.value.activeDirection)
        assertFalse(transport.sentCommands().any { it.startsWith("V 0.153") })
        assertTrue(sm.state.value.interlockWarning?.contains("telemetry stale", ignoreCase = true) == true)
    }

    @Test
    fun configureDoesNotSendStaleStopToNewHost() = runTest {
        val transport = FakeHttpTransport()
        val clock = FakeClock(1_000L)
        val oldCfg = ConnectionConfig(host = "robot-old.local", apiPort = 8080, camPort = 8081, demoMode = false)
        val newCfg = ConnectionConfig(host = "robot-new.local", apiPort = 9090, camPort = 9091, demoMode = false)
        val sm = CockpitStateMachine(backgroundScope, AmrApiClient(transport), clock, DemoSimulatorEngine(), oldCfg)
        sm.pollTelemetryOnce()
        sm.onDirectionHoldStart(DriveDirection.F)
        runCurrent()

        transport.requests.clear()
        sm.configure(newCfg)
        runCurrent()

        assertEquals(newCfg, sm.state.value.config)
        assertFalse(sm.state.value.stopPending)
        assertNull(sm.state.value.lastCommandNotice)
        assertFalse(transport.requests.any { it.contains("robot-new.local:9090") })
        assertTrue(transport.requests.any { it.contains("robot-old.local:8080") && it.contains("STOP") })
    }
}
