package com.yin1090.amrcontroller.state

interface ClockProvider {
    fun nowMs(): Long
}

class SystemClockProvider : ClockProvider {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
