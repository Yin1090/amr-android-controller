package com.yin1090.amrcontroller.domain

import com.yin1090.amrcontroller.model.DriveDirection
import com.yin1090.amrcontroller.model.TwistCommand
import com.yin1090.amrcontroller.model.WheelRpmPair
import java.util.Locale
import kotlin.math.abs

object Kinematics {
    const val WHEEL_RADIUS_M = 0.0366
    const val HALF_TRACK_M = 0.096
    const val K_MPS_PER_RPM = 2.0 * Math.PI * WHEEL_RADIUS_M / 60.0
    const val MIN_RPM = 40
    const val MAX_RPM = 135
    const val DEFAULT_RPM = 40
    const val RPM_STEP = 10
    const val ANGULAR_GAIN = 0.60
    const val MAX_OMEGA_RAD_S = 1.80
    const val DIAGONAL_FACTOR = 0.707
    const val STOP_COMMAND = "V 0.000 0.000"

    fun clampRpm(rpm: Int): Int = rpm.coerceIn(MIN_RPM, MAX_RPM)

    fun adjustRpm(currentRpm: Int, delta: Int): Int = clampRpm(currentRpm + delta)

    fun linearSpeedMps(rpm: Int): Double = clampRpm(rpm) * K_MPS_PER_RPM

    fun angularSpeedRadPerSec(rpm: Int): Double {
        val v = linearSpeedMps(rpm)
        return minOf(MAX_OMEGA_RAD_S, ANGULAR_GAIN * v / HALF_TRACK_M)
    }

    fun computeTwist(rpm: Int, direction: DriveDirection): TwistCommand {
        val v = linearSpeedMps(rpm)
        val w = angularSpeedRadPerSec(rpm)
        return when (direction) {
            DriveDirection.F -> TwistCommand(+v, 0.0)
            DriveDirection.B -> TwistCommand(-v, 0.0)
            DriveDirection.L -> TwistCommand(0.0, +w)
            DriveDirection.R -> TwistCommand(0.0, -w)
            DriveDirection.FL -> TwistCommand(+v * DIAGONAL_FACTOR, +w * DIAGONAL_FACTOR)
            DriveDirection.FR -> TwistCommand(+v * DIAGONAL_FACTOR, -w * DIAGONAL_FACTOR)
            DriveDirection.BL -> TwistCommand(-v * DIAGONAL_FACTOR, +w * DIAGONAL_FACTOR)
            DriveDirection.BR -> TwistCommand(-v * DIAGONAL_FACTOR, -w * DIAGONAL_FACTOR)
        }
    }

    fun twistToWheelRpm(v: Double, w: Double): WheelRpmPair {
        val rpmLeft = (v - w * HALF_TRACK_M) / K_MPS_PER_RPM
        val rpmRight = (v + w * HALF_TRACK_M) / K_MPS_PER_RPM
        return WheelRpmPair(leftRpm = rpmLeft, rightRpm = rpmRight)
    }

    fun formatVelocityCommand(v: Double, w: Double): String {
        val cleanV = if (!v.isFinite() || abs(v) < 0.0005) 0.0 else v
        val cleanW = if (!w.isFinite() || abs(w) < 0.0005) 0.0 else w
        return String.format(Locale.US, "V %.3f %.3f", cleanV, cleanW)
    }
}
