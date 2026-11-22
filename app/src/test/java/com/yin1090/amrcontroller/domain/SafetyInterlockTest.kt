package com.yin1090.amrcontroller.domain

import com.yin1090.amrcontroller.model.ArmState
import com.yin1090.amrcontroller.model.DriveDirection
import com.yin1090.amrcontroller.model.LidarHealth
import com.yin1090.amrcontroller.model.SafetyZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyInterlockTest {

    @Test
    fun allEightDirectionsMapToExpectedZonesAndIsolateBlocking() {
        val expectedKeys = mapOf(
            DriveDirection.F to "front",
            DriveDirection.B to "back",
            DriveDirection.L to "left",
            DriveDirection.R to "right",
            DriveDirection.FL to "front_left",
            DriveDirection.FR to "front_right",
            DriveDirection.BL to "back_left",
            DriveDirection.BR to "back_right"
        )

        for ((blockedDir, expectedKey) in expectedKeys) {
            assertEquals(expectedKey, SafetyInterlock.zoneDescriptor(blockedDir).jsonKey)
            val zones = SafetyZones(
                front = if (blockedDir == DriveDirection.F) 39.9 else 999.0,
                back = if (blockedDir == DriveDirection.B) 39.9 else 999.0,
                left = if (blockedDir == DriveDirection.L) 39.9 else 999.0,
                right = if (blockedDir == DriveDirection.R) 39.9 else 999.0,
                frontLeft = if (blockedDir == DriveDirection.FL) 39.9 else 999.0,
                frontRight = if (blockedDir == DriveDirection.FR) 39.9 else 999.0,
                backLeft = if (blockedDir == DriveDirection.BL) 39.9 else 999.0,
                backRight = if (blockedDir == DriveDirection.BR) 39.9 else 999.0
            )
            for (dir in DriveDirection.entries) {
                val decision = SafetyInterlock.evaluateDrive(
                    direction = dir,
                    armState = ArmState.ARMED,
                    stopPending = false,
                    demoMode = false,
                    lidarOk = true,
                    telemetryStale = false,
                    zones = zones
                )
                if (dir == blockedDir) {
                    assertTrue(decision is InterlockDecision.Blocked)
                } else {
                    assertEquals(InterlockDecision.Allowed, decision)
                }
            }
        }
    }

    @Test
    fun thresholdBoundary399BlocksAnd400Allows() {
        val blocked = SafetyInterlock.evaluateDrive(
            direction = DriveDirection.F,
            armState = ArmState.ARMED,
            stopPending = false,
            demoMode = false,
            lidarOk = true,
            telemetryStale = false,
            zones = SafetyZones(front = 39.9)
        )
        assertTrue(blocked is InterlockDecision.Blocked)

        val allowed = SafetyInterlock.evaluateDrive(
            direction = DriveDirection.F,
            armState = ArmState.ARMED,
            stopPending = false,
            demoMode = false,
            lidarOk = true,
            telemetryStale = false,
            zones = SafetyZones(front = 40.0)
        )
        assertEquals(InterlockDecision.Allowed, allowed)

        val zeroBlocked = SafetyInterlock.evaluateDrive(
            direction = DriveDirection.F,
            armState = ArmState.ARMED,
            stopPending = false,
            demoMode = false,
            lidarOk = true,
            telemetryStale = false,
            zones = SafetyZones(front = 0.0)
        )
        assertTrue(zeroBlocked is InterlockDecision.Blocked)
    }

    @Test
    fun disconnectedLidarBlocksAllMotion() {
        val lidarOk = SafetyInterlock.isLidarHealthy(LidarHealth(connected = false, ageSeconds = 0.05))
        assertFalse(lidarOk)
        for (dir in DriveDirection.entries) {
            val decision = SafetyInterlock.evaluateDrive(
                direction = dir,
                armState = ArmState.ARMED,
                stopPending = false,
                demoMode = false,
                lidarOk = lidarOk,
                telemetryStale = false,
                zones = SafetyZones.ALL_CLEAR
            )
            assertTrue(decision is InterlockDecision.Blocked)
        }
    }

    @Test
    fun staleLidarAgeAtOrAbove05SecondsBlocks() {
        assertTrue(SafetyInterlock.isLidarHealthy(LidarHealth(connected = true, ageSeconds = 0.49)))
        assertFalse(SafetyInterlock.isLidarHealthy(LidarHealth(connected = true, ageSeconds = 0.50)))
        assertFalse(SafetyInterlock.isLidarHealthy(LidarHealth(connected = true, ageSeconds = 0.51)))
        assertFalse(SafetyInterlock.isLidarHealthy(LidarHealth(connected = true, ageSeconds = Double.NaN)))
        assertFalse(SafetyInterlock.isLidarHealthy(LidarHealth(connected = true, ageSeconds = -0.1)))
    }

    @Test
    fun telemetryWatchdogOver1500MsBlocksAndExpires() {
        assertEquals(1_500L, SafetyInterlock.TELEMETRY_WATCHDOG_MS)
        assertFalse(SafetyInterlock.isTelemetryWatchdogExpired(lastTelemetryAtMs = 1_000L, nowMs = 2_500L))
        assertTrue(SafetyInterlock.isTelemetryWatchdogExpired(lastTelemetryAtMs = 1_000L, nowMs = 2_501L))
        assertTrue(SafetyInterlock.isTelemetryWatchdogExpired(lastTelemetryAtMs = 0L, nowMs = 500L))

        val decision = SafetyInterlock.evaluateDrive(
            direction = DriveDirection.F,
            armState = ArmState.ARMED,
            stopPending = false,
            demoMode = false,
            lidarOk = true,
            telemetryStale = true,
            zones = SafetyZones.ALL_CLEAR
        )
        assertTrue(decision is InterlockDecision.Blocked)
    }

    @Test
    fun nonFiniteOrNegativeZoneFailsClosed() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val decision = SafetyInterlock.evaluateDrive(
                direction = DriveDirection.F,
                armState = ArmState.ARMED,
                stopPending = false,
                demoMode = false,
                lidarOk = true,
                telemetryStale = false,
                zones = SafetyZones(front = bad)
            )
            assertTrue(decision is InterlockDecision.Blocked)
        }
    }

    @Test
    fun disarmedAndFaultStatesBlockDrive() {
        val disarmed = SafetyInterlock.evaluateDrive(
            direction = DriveDirection.F,
            armState = ArmState.DISARMED,
            stopPending = false,
            demoMode = false,
            lidarOk = true,
            telemetryStale = false,
            zones = SafetyZones.ALL_CLEAR
        )
        assertTrue(disarmed is InterlockDecision.Blocked)
        assertEquals("NOT_ARMED", (disarmed as InterlockDecision.Blocked).reasonCode)

        val faulted = SafetyInterlock.evaluateDrive(
            direction = DriveDirection.F,
            armState = ArmState.FAULT,
            stopPending = false,
            demoMode = false,
            lidarOk = true,
            telemetryStale = false,
            zones = SafetyZones.ALL_CLEAR
        )
        assertTrue(faulted is InterlockDecision.Blocked)
        assertEquals("FAULT_ACTIVE", (faulted as InterlockDecision.Blocked).reasonCode)
    }

    @Test
    fun oppositeZoneObstacleDoesNotBlockReverse() {
        val zones = SafetyZones(front = 25.0, back = 150.0)
        val revDecision = SafetyInterlock.evaluateDrive(
            direction = DriveDirection.B,
            armState = ArmState.ARMED,
            stopPending = false,
            demoMode = false,
            lidarOk = true,
            telemetryStale = false,
            zones = zones
        )
        assertEquals(InterlockDecision.Allowed, revDecision)
    }

    @Test
    fun clearTelemetryAllowsAllEightDirections() {
        for (dir in DriveDirection.entries) {
            val decision = SafetyInterlock.evaluateDrive(
                direction = dir,
                armState = ArmState.ARMED,
                stopPending = false,
                demoMode = false,
                lidarOk = true,
                telemetryStale = false,
                zones = SafetyZones.ALL_CLEAR
            )
            assertEquals(InterlockDecision.Allowed, decision)
        }
    }
}
