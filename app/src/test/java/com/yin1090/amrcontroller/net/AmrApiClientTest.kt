package com.yin1090.amrcontroller.net

import com.yin1090.amrcontroller.model.ConnectionConfig
import com.yin1090.amrcontroller.model.PidGains
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class AmrApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AmrApiClient
    private lateinit var config: ConnectionConfig
    private val defaultLocale: Locale = Locale.getDefault()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AmrApiClient(OkHttpTransport())
        config = ConnectionConfig(
            host = server.hostName,
            apiPort = server.port,
            camPort = 8081
        )
    }

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
        server.shutdown()
    }

    @Test
    fun telemetryParsesAllFieldsAndEightZones() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": "WARNING",
                  "min_m": 0.55,
                  "min_ang": 45.0,
                  "robot": {"x": 1.2, "y": -0.4, "th": 0.5, "l_rpm": 40.0, "r_rpm": 40.0},
                  "control": {"armed": true, "fault": null},
                  "lidar": {"connected": true, "age_s": 0.12},
                  "ranges": [0.55, 1.2, 2.4],
                  "map_info": {"robot_pose": [1.2, -0.4, 0.5]},
                  "zones": {
                    "front": 55.0, "back": 210.0, "left": 180.0, "right": 190.0,
                    "front_left": 55.0, "front_right": 90.0, "back_left": 200.0, "back_right": 205.0
                  }
                }
                """.trimIndent()
            )
        )

        val result = client.fetchTelemetry(config, nowMs = 10_000L)
        assertTrue(result is ApiResult.Success)
        val snap = (result as ApiResult.Success).data
        assertEquals("WARNING", snap.status)
        assertEquals(0.55, snap.minDistanceM, 1e-6)
        assertEquals(1.2, snap.robot.x, 1e-6)
        assertTrue(snap.control.armed)
        assertNull(snap.control.fault)
        assertTrue(snap.lidar.connected)
        assertEquals(0.12, snap.lidar.ageSeconds, 1e-6)
        assertEquals(3, snap.ranges.size)
        assertNotNull(snap.mapRobotPose)
        assertEquals(55.0, snap.zones.front, 1e-6)
        assertEquals(55.0, snap.zones.frontLeft, 1e-6)
    }

    @Test
    fun telemetryFailsClosedWhenZonesOrAgeMissing() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": "SAFE",
                  "robot": {"x": 0.0, "y": 0.0, "th": 0.0, "l_rpm": 0.0, "r_rpm": 0.0},
                  "control": {"armed": false, "fault": "ENCODER_FAULT"},
                  "lidar": {"connected": true, "age_s": null}
                }
                """.trimIndent()
            )
        )

        val result = client.fetchTelemetry(config) as ApiResult.Success
        assertEquals("ENCODER_FAULT", result.data.control.fault)
        assertTrue(result.data.lidar.ageSeconds.isInfinite())
        assertEquals(0.0, result.data.zones.front, 1e-6)
        assertEquals(0.0, result.data.zones.backRight, 1e-6)
    }

    @Test
    fun sendCommandFormatsExactQueryParamsWithEpochAndSeq() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"SENT"}"""))

        val res = client.sendCommand(config, "V 0.153 0.000", epoch = 3L, seq = 12L)
        assertTrue(res is ApiResult.Success)
        assertTrue((res as ApiResult.Success).data.isSent)

        val recorded = server.takeRequest()
        val reqUrl = recorded.requestUrl!!
        assertEquals("/api/cmd", reqUrl.encodedPath)
        assertEquals("V 0.153 0.000", reqUrl.queryParameter("val"))
        assertEquals("3", reqUrl.queryParameter("epoch"))
        assertEquals("12", reqUrl.queryParameter("seq"))
    }

    @Test
    fun localeUsFormattingUnderCommaDecimalLocale() = runTest {
        Locale.setDefault(Locale.GERMANY)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"SENT"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"OK"}"""))

        client.sendCommand(config, "V 0.249 -1.557", epoch = 1L, seq = 1L)
        client.applyPid(config, PidGains(0.5f, 1.5f, 0.01f))

        val cmdReq = server.takeRequest().requestUrl!!
        assertEquals("V 0.249 -1.557", cmdReq.queryParameter("val"))

        val pidReq = server.takeRequest().requestUrl!!
        assertEquals("/api/pid", pidReq.encodedPath)
        assertEquals("0.5", pidReq.queryParameter("kp"))
        assertEquals("1.5", pidReq.queryParameter("ki"))
        assertEquals("0.01", pidReq.queryParameter("kd"))
        assertFalse(pidReq.toString().contains(","))
    }

    @Test
    fun mapFullParsesValidPayload() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"w":3,"h":2,"res":0.05,"ox":-1.0,"oy":-2.0,"yaw":0.25,"data":[0,50,100,-1,20,80]}"""
            )
        )
        val res = client.fetchFullMap(config, nowMs = 5_000L)
        assertTrue(res is ApiResult.Success)
        val map = (res as ApiResult.Success).data
        assertEquals(3, map.width)
        assertEquals(2, map.height)
        assertEquals(0.05f, map.resolutionM, 1e-6f)
        assertEquals(6, map.data.size)
        assertEquals(100, map.data[2])
    }

    @Test
    fun mapFullRejectsOver4MillionCellsAndMismatchedLength() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"w":2001,"h":2001,"res":0.05,"ox":0.0,"oy":0.0,"yaw":0.0,"data":[]}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"w":10,"h":10,"res":0.05,"ox":0.0,"oy":0.0,"yaw":0.0,"data":[0,1]}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"w":2,"h":2,"res":0.0,"ox":0.0,"oy":0.0,"yaw":0.0,"data":[0,0,0,0]}"""
            )
        )

        assertTrue(client.fetchFullMap(config) is ApiResult.ValidationError)
        assertTrue(client.fetchFullMap(config) is ApiResult.ValidationError)
        assertTrue(client.fetchFullMap(config) is ApiResult.ValidationError)
    }

    @Test
    fun autoConfigPidAndResetMapEndpointsAndQueries() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"OK"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"OK"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"OK"}"""))

        assertTrue(client.setAutoConfig(config, 65) is ApiResult.Success)
        assertTrue(client.applyPid(config, PidGains(0.5f, 1.5f, 0.01f)) is ApiResult.Success)
        assertTrue(client.resetMap(config) is ApiResult.Success)

        val autoReq = server.takeRequest().requestUrl!!
        assertEquals("/api/auto_config", autoReq.encodedPath)
        assertEquals("65", autoReq.queryParameter("speed"))
        assertEquals("65", autoReq.queryParameter("turn"))

        val pidReq = server.takeRequest().requestUrl!!
        assertEquals("/api/pid", pidReq.encodedPath)

        val resetReq = server.takeRequest().requestUrl!!
        assertEquals("/api/reset_map", resetReq.encodedPath)
    }

    @Test
    fun non200TelemetryAndCmdRejectionHandledGracefully() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"NO_TRANSPORT"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"REJECTED","reason":"FAULT_ACTIVE"}"""))

        val telRes = client.fetchTelemetry(config)
        assertTrue(telRes is ApiResult.HttpError)
        assertEquals(503, (telRes as ApiResult.HttpError).httpCode)

        val cmdRes = client.sendCommand(config, "ARM", epoch = 1L, seq = 0L)
        assertTrue(cmdRes is ApiResult.Success)
        val cmd = (cmdRes as ApiResult.Success).data
        assertFalse(cmd.isSent)
        assertEquals("REJECTED", cmd.status)
        assertEquals("FAULT_ACTIVE", cmd.reason)
    }

    @Test
    fun streamAndMissionUrlConstruction() {
        val custom = ConnectionConfig(host = "robot.local", apiPort = 8080, camPort = 8081)
        assertEquals("http://robot.local:8081/stream?t=123456", client.buildCameraStreamUrl(custom, 123456L))
        assertEquals("http://robot.local:8080/?compact=1", client.buildMissionUrl(custom))
    }

    @Test
    fun defaultConfigContainsZeroPrivateLanIps() {
        val def = ConnectionConfig()
        val p1 = listOf("192", "168").joinToString("\\.") + "\\."
        val p2 = "10\\."
        val p3 = "172\\.(1[6-9]|2\\d|3[01])\\."
        val privateLanRegex = Regex("^($p1|$p2|$p3)")
        assertFalse(privateLanRegex.containsMatchIn(def.host))
        assertEquals("127.0.0.1", def.host)
        assertEquals(8080, def.apiPort)
        assertEquals(8081, def.camPort)
    }

    @Test
    fun telemetryAndCmdHandleJsonNullStatusAndBlockersGracefully() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": null,
                  "robot": {"x": 0.0, "y": 0.0, "th": 0.0, "l_rpm": 0.0, "r_rpm": 0.0},
                  "control": {"armed": true, "fault": null},
                  "lidar": {"connected": true, "age_s": 0.1},
                  "zones": {
                    "front": 100.0, "back": 100.0, "left": 100.0, "right": 100.0,
                    "front_left": 100.0, "front_right": 100.0, "back_left": 100.0, "back_right": 100.0
                  }
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status": null, "reason": null, "blockers": [null, "", "FAULT"]}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"status": null}"""
            )
        )

        val telRes = client.fetchTelemetry(config) as ApiResult.Success
        assertEquals("UNKNOWN", telRes.data.status)

        val cmd200 = (client.sendCommand(config, "ARM", epoch = 1L, seq = 0L) as ApiResult.Success).data
        assertEquals("SENT", cmd200.status)
        assertNull(cmd200.reason)
        assertEquals(listOf("FAULT"), cmd200.blockers)

        val cmd422 = (client.sendCommand(config, "ARM", epoch = 1L, seq = 1L) as ApiResult.Success).data
        assertEquals("FAILED", cmd422.status)
        assertFalse(cmd422.isSent)
    }

    @Test
    fun formatPidFloatNormalizesNegativeZero() {
        val negZero = AmrApiClient.formatPidFloat(-0.0f)
        assertEquals("0.0", negZero)
        assertFalse(negZero.startsWith("-"))

        val tinyNeg = AmrApiClient.formatPidFloat(-1e-7f)
        assertEquals("0.0", tinyNeg)
        assertFalse(tinyNeg.startsWith("-"))

        assertEquals("0.01", AmrApiClient.formatPidFloat(0.01f))
    }
}
