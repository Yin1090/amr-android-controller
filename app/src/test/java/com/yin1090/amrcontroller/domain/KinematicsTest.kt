package com.yin1090.amrcontroller.domain

import com.yin1090.amrcontroller.model.DriveDirection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class KinematicsTest {

    private val defaultLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun constantsMatchHardwareSpec() {
        assertEquals(0.0366, Kinematics.WHEEL_RADIUS_M, 1e-6)
        assertEquals(0.096, Kinematics.HALF_TRACK_M, 1e-6)
        assertEquals(2.0 * Math.PI * 0.0366 / 60.0, Kinematics.K_MPS_PER_RPM, 1e-9)
        assertEquals(1.80, Kinematics.MAX_OMEGA_RAD_S, 1e-6)
        assertEquals(0.707, Kinematics.DIAGONAL_FACTOR, 1e-6)
        assertEquals(40, Kinematics.MIN_RPM)
        assertEquals(135, Kinematics.MAX_RPM)
        assertEquals(40, Kinematics.DEFAULT_RPM)
        assertEquals(10, Kinematics.RPM_STEP)
    }

    @Test
    fun linearAndAngularSpeedsAt40Rpm() {
        val v = Kinematics.linearSpeedMps(40)
        val w = Kinematics.angularSpeedRadPerSec(40)
        assertEquals(0.153310, v, 1e-4)
        assertEquals(0.958186, w, 1e-4)
        assertEquals("V 0.153 0.000", Kinematics.formatVelocityCommand(v, 0.0))
    }

    @Test
    fun linearAndAngularSpeedsAt65Rpm() {
        val v = Kinematics.linearSpeedMps(65)
        val w = Kinematics.angularSpeedRadPerSec(65)
        assertEquals(0.249128, v, 1e-4)
        assertEquals(1.557052, w, 1e-4)
        assertEquals("V 0.249 1.557", Kinematics.formatVelocityCommand(v, w))
    }

    @Test
    fun angularSpeedCapsAt135Rpm() {
        val v = Kinematics.linearSpeedMps(135)
        val w = Kinematics.angularSpeedRadPerSec(135)
        assertEquals(0.517420, v, 1e-4)
        assertEquals(1.80, w, 1e-6)
    }

    @Test
    fun cardinalDirectionsAt40Rpm() {
        val v = Kinematics.linearSpeedMps(40)
        val w = Kinematics.angularSpeedRadPerSec(40)

        val fwd = Kinematics.computeTwist(40, DriveDirection.F)
        assertEquals(v, fwd.linearMps, 1e-6)
        assertEquals(0.0, fwd.angularRadPerSec, 1e-6)

        val back = Kinematics.computeTwist(40, DriveDirection.B)
        assertEquals(-v, back.linearMps, 1e-6)
        assertEquals(0.0, back.angularRadPerSec, 1e-6)

        val left = Kinematics.computeTwist(40, DriveDirection.L)
        assertEquals(0.0, left.linearMps, 1e-6)
        assertEquals(w, left.angularRadPerSec, 1e-6)

        val right = Kinematics.computeTwist(40, DriveDirection.R)
        assertEquals(0.0, right.linearMps, 1e-6)
        assertEquals(-w, right.angularRadPerSec, 1e-6)
    }

    @Test
    fun diagonalDirectionsApply0707Scaling() {
        val v = Kinematics.linearSpeedMps(40)
        val w = Kinematics.angularSpeedRadPerSec(40)
        val f = Kinematics.DIAGONAL_FACTOR

        val fl = Kinematics.computeTwist(40, DriveDirection.FL)
        assertEquals(v * f, fl.linearMps, 1e-6)
        assertEquals(w * f, fl.angularRadPerSec, 1e-6)

        val fr = Kinematics.computeTwist(40, DriveDirection.FR)
        assertEquals(v * f, fr.linearMps, 1e-6)
        assertEquals(-w * f, fr.angularRadPerSec, 1e-6)

        val bl = Kinematics.computeTwist(40, DriveDirection.BL)
        assertEquals(-v * f, bl.linearMps, 1e-6)
        assertEquals(w * f, bl.angularRadPerSec, 1e-6)

        val br = Kinematics.computeTwist(40, DriveDirection.BR)
        assertEquals(-v * f, br.linearMps, 1e-6)
        assertEquals(-w * f, br.angularRadPerSec, 1e-6)
    }

    @Test
    fun diagonalAngularVelocityNeverExceedsCap() {
        for (dir in listOf(DriveDirection.FL, DriveDirection.FR, DriveDirection.BL, DriveDirection.BR)) {
            val twist = Kinematics.computeTwist(135, dir)
            assertTrue(kotlin.math.abs(twist.angularRadPerSec) <= Kinematics.MAX_OMEGA_RAD_S + 1e-9)
        }
    }

    @Test
    fun inverseWheelRpmsMatchDifferentialDrive() {
        val fwd = Kinematics.computeTwist(40, DriveDirection.F)
        val fwdRpm = Kinematics.twistToWheelRpm(fwd.linearMps, fwd.angularRadPerSec)
        assertEquals(40.0, fwdRpm.leftRpm, 1e-4)
        assertEquals(40.0, fwdRpm.rightRpm, 1e-4)

        val back = Kinematics.computeTwist(40, DriveDirection.B)
        val backRpm = Kinematics.twistToWheelRpm(back.linearMps, back.angularRadPerSec)
        assertEquals(-40.0, backRpm.leftRpm, 1e-4)
        assertEquals(-40.0, backRpm.rightRpm, 1e-4)

        val left = Kinematics.computeTwist(40, DriveDirection.L)
        val leftRpm = Kinematics.twistToWheelRpm(left.linearMps, left.angularRadPerSec)
        assertTrue(leftRpm.leftRpm < 0.0 && leftRpm.rightRpm > 0.0)
        assertEquals(-leftRpm.leftRpm, leftRpm.rightRpm, 1e-6)

        val stopRpm = Kinematics.twistToWheelRpm(0.0, 0.0)
        assertEquals(0.0, stopRpm.leftRpm, 1e-9)
        assertEquals(0.0, stopRpm.rightRpm, 1e-9)
    }

    @Test
    fun rpmClampingAndStep10Behavior() {
        assertEquals(40, Kinematics.clampRpm(10))
        assertEquals(40, Kinematics.clampRpm(40))
        assertEquals(135, Kinematics.clampRpm(135))
        assertEquals(135, Kinematics.clampRpm(200))

        assertEquals(50, Kinematics.adjustRpm(40, 10))
        assertEquals(135, Kinematics.adjustRpm(130, 10))
        assertEquals(135, Kinematics.adjustRpm(135, 10))
        assertEquals(40, Kinematics.adjustRpm(40, -10))
        assertEquals(40, Kinematics.adjustRpm(45, -10))
        assertEquals(55, Kinematics.adjustRpm(65, -10))
    }

    @Test
    fun commandFormattingUsesLocaleUsInNonUsDefaultLocale() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("V 0.153 -0.958", Kinematics.formatVelocityCommand(0.1533, -0.9582))
        assertEquals("V 0.000 0.000", Kinematics.formatVelocityCommand(-0.0001, -0.0))
    }
}
