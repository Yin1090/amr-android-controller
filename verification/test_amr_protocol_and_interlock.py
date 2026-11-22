"""Host-runnable protocol, kinematics, watchdog, and active 8-zone < 40 cm interlock verification."""

from __future__ import annotations

import json
import math
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

import pytest

VERIFICATION_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = VERIFICATION_DIR.parent
if str(VERIFICATION_DIR) not in sys.path:
    sys.path.insert(0, str(VERIFICATION_DIR))

from mock_amr_server import MockAmrServer  # noqa: E402

KINEMATICS_KT = PROJECT_ROOT / "app/src/main/java/com/yin1090/amrcontroller/domain/Kinematics.kt"
INTERLOCK_KT = PROJECT_ROOT / "app/src/main/java/com/yin1090/amrcontroller/domain/SafetyInterlock.kt"
STATE_MACHINE_KT = PROJECT_ROOT / "app/src/main/java/com/yin1090/amrcontroller/state/CockpitStateMachine.kt"
API_CLIENT_KT = PROJECT_ROOT / "app/src/main/java/com/yin1090/amrcontroller/net/AmrApiClient.kt"


def _extract_kt_double(src: str, name: str) -> float:
    m = re.search(rf"const\s+val\s+{name}\s*=\s*([0-9._]+)", src)
    assert m, f"Missing Kotlin constant {name}"
    return float(m.group(1).replace("_", ""))


def _extract_kt_int(src: str, name: str) -> int:
    m = re.search(rf"const\s+val\s+{name}\s*=\s*([0-9_]+)L?", src)
    assert m, f"Missing Kotlin constant {name}"
    return int(m.group(1).replace("_", ""))


class ParityDriver:
    """Protocol driver configured strictly from extracted Kotlin source constants."""

    def __init__(self, base_url: str) -> None:
        kin_src = KINEMATICS_KT.read_text(encoding="utf-8")
        safe_src = INTERLOCK_KT.read_text(encoding="utf-8")
        sm_src = STATE_MACHINE_KT.read_text(encoding="utf-8")

        self.wheel_radius_m = _extract_kt_double(kin_src, "WHEEL_RADIUS_M")
        self.half_track_m = _extract_kt_double(kin_src, "HALF_TRACK_M")
        self.k_mps_per_rpm = 2.0 * math.pi * self.wheel_radius_m / 60.0
        self.min_rpm = _extract_kt_int(kin_src, "MIN_RPM")
        self.max_rpm = _extract_kt_int(kin_src, "MAX_RPM")
        self.angular_gain = _extract_kt_double(kin_src, "ANGULAR_GAIN")
        self.max_omega = _extract_kt_double(kin_src, "MAX_OMEGA_RAD_S")
        self.diagonal_factor = _extract_kt_double(kin_src, "DIAGONAL_FACTOR")

        self.obstacle_stop_cm = _extract_kt_double(safe_src, "OBSTACLE_STOP_THRESHOLD_CM")
        self.lidar_max_age_s = _extract_kt_double(safe_src, "LIDAR_MAX_AGE_SECONDS")
        self.watchdog_ms = _extract_kt_int(safe_src, "TELEMETRY_WATCHDOG_MS")
        self.hold_repeat_ms = _extract_kt_int(sm_src, "HOLD_REPEAT_MS")

        self.base_url = base_url
        self.epoch = 1
        self.seq = 0
        self.armed = False
        self.active_dir: str | None = None
        self.last_telemetry_ms = 0
        self.latest_telemetry: dict[str, Any] | None = None

    def _get(self, path_and_query: str) -> tuple[int, bytes]:
        url = f"{self.base_url}{path_and_query}"
        try:
            with urllib.request.urlopen(url, timeout=4.0) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as err:
            return err.code, err.read()

    def compute_twist(self, rpm: int, direction: str) -> tuple[float, float]:
        clamped = max(self.min_rpm, min(self.max_rpm, rpm))
        v = clamped * self.k_mps_per_rpm
        w = min(self.max_omega, self.angular_gain * v / self.half_track_m)
        f = self.diagonal_factor
        mapping = {
            "F": (+v, 0.0),
            "B": (-v, 0.0),
            "L": (0.0, +w),
            "R": (0.0, -w),
            "FL": (+v * f, +w * f),
            "FR": (+v * f, -w * f),
            "BL": (-v * f, +w * f),
            "BR": (-v * f, -w * f),
        }
        return mapping[direction]

    @staticmethod
    def format_v_cmd(v: float, w: float) -> str:
        cv = 0.0 if abs(v) < 0.0005 else v
        cw = 0.0 if abs(w) < 0.0005 else w
        return f"V {cv:.3f} {cw:.3f}"

    def send_cmd(self, cmd: str) -> tuple[int, dict[str, Any]]:
        q = urllib.parse.quote(cmd, safe="")
        code, raw = self._get(f"/api/cmd?val={q}&epoch={self.epoch}&seq={self.seq}")
        return code, json.loads(raw.decode("utf-8"))

    def poll_telemetry(self, now_ms: int) -> tuple[int, dict[str, Any] | None]:
        code, raw = self._get("/api/telemetry")
        if code == 200:
            data = json.loads(raw.decode("utf-8"))
            self.latest_telemetry = data
            self.last_telemetry_ms = now_ms
            self.armed = bool(data["control"]["armed"])
            if self.active_dir is not None and not self.can_drive(self.active_dir, now_ms):
                self.auto_halt()
            return code, data
        return code, None

    def can_drive(self, direction: str, now_ms: int) -> bool:
        if not self.armed or self.latest_telemetry is None:
            return False
        if now_ms - self.last_telemetry_ms > self.watchdog_ms:
            return False
        lidar = self.latest_telemetry["lidar"]
        age_s = float(lidar["age_s"])
        if not lidar["connected"] or not math.isfinite(age_s) or age_s < 0.0 or age_s >= self.lidar_max_age_s:
            return False
        zone_map = {
            "F": "front",
            "B": "back",
            "L": "left",
            "R": "right",
            "FL": "front_left",
            "FR": "front_right",
            "BL": "back_left",
            "BR": "back_right",
        }
        raw_dist = self.latest_telemetry["zones"].get(zone_map[direction])
        if raw_dist is None:
            return False
        try:
            dist_cm = float(raw_dist)
        except (TypeError, ValueError):
            return False
        return math.isfinite(dist_cm) and dist_cm >= self.obstacle_stop_cm

    def press_direction(self, direction: str, rpm: int, now_ms: int) -> bool:
        if not self.can_drive(direction, now_ms):
            return False
        self.epoch += 1
        self.active_dir = direction
        v, w = self.compute_twist(rpm, direction)
        code, body = self.send_cmd(self.format_v_cmd(v, w))
        if code == 200 and body.get("status") == "SENT":
            self.seq += 1
        return True

    def release_direction(self) -> None:
        self.epoch += 1
        self.active_dir = None
        self.send_cmd("V 0.000 0.000")

    def auto_halt(self) -> None:
        self.epoch += 1
        self.active_dir = None
        self.send_cmd("V 0.000 0.000")


def test_kotlin_source_constants_match_contract() -> None:
    kin_src = KINEMATICS_KT.read_text(encoding="utf-8")
    safe_src = INTERLOCK_KT.read_text(encoding="utf-8")
    api_src = API_CLIENT_KT.read_text(encoding="utf-8")

    assert _extract_kt_double(kin_src, "WHEEL_RADIUS_M") == pytest.approx(0.0366)
    assert _extract_kt_double(kin_src, "HALF_TRACK_M") == pytest.approx(0.096)
    assert _extract_kt_int(kin_src, "MIN_RPM") == 40
    assert _extract_kt_int(kin_src, "MAX_RPM") == 135
    assert _extract_kt_double(kin_src, "MAX_OMEGA_RAD_S") == pytest.approx(1.80)
    assert _extract_kt_double(kin_src, "DIAGONAL_FACTOR") == pytest.approx(0.707)
    assert _extract_kt_double(safe_src, "OBSTACLE_STOP_THRESHOLD_CM") == pytest.approx(40.0)
    assert _extract_kt_double(safe_src, "LIDAR_MAX_AGE_SECONDS") == pytest.approx(0.5)
    assert _extract_kt_int(safe_src, "TELEMETRY_WATCHDOG_MS") == 1500
    assert "/api/telemetry" in api_src
    assert "/api/map_full" in api_src
    assert "/api/cmd" in api_src


def test_telemetry_probe_200_404_and_503() -> None:
    with MockAmrServer() as srv:
        drv = ParityDriver(srv.base_url)
        code, data = drv.poll_telemetry(1000)
        assert code == 200
        assert data is not None and data["status"] == "SAFE"

        with srv.state.lock:
            srv.state.telemetry_http_status = 404
        code404, _ = drv.poll_telemetry(1400)
        assert code404 == 404

        with srv.state.lock:
            srv.state.telemetry_http_status = 503
        code503, _ = drv.poll_telemetry(1800)
        assert code503 == 503


def test_live_kinematics_8_directions_and_wire_format() -> None:
    with MockAmrServer() as srv:
        drv = ParityDriver(srv.base_url)
        drv.send_cmd("ARM")
        drv.poll_telemetry(1000)

        for rpm in (40, 65, 135):
            for direction in ("F", "B", "L", "R", "FL", "FR", "BL", "BR"):
                assert drv.press_direction(direction, rpm, 1000)
                v, w = drv.compute_twist(rpm, direction)
                assert abs(w) <= 1.80 + 1e-9
                last = srv.state.cmd_log[-1]
                assert last["val"] == drv.format_v_cmd(v, w)
                assert "," not in last["val"]


def test_arm_fault_clear_fault_disarm_and_stop() -> None:
    with MockAmrServer() as srv:
        drv = ParityDriver(srv.base_url)
        with srv.state.lock:
            srv.state.fault = "OVERCURRENT"

        code, resp = drv.send_cmd("ARM")
        assert code == 422
        assert resp["status"] == "REJECTED"

        code_cf, resp_cf = drv.send_cmd("CLEAR_FAULT")
        assert code_cf == 200 and resp_cf["status"] == "SENT"

        code_arm, resp_arm = drv.send_cmd("ARM")
        assert code_arm == 200 and resp_arm["status"] == "SENT"

        drv.send_cmd("DISARM")
        assert not srv.state.armed
        drv.send_cmd("ARM")
        drv.send_cmd("STOP")
        assert not srv.state.armed


def test_timed_hold_repeat_and_release_to_stop() -> None:
    with MockAmrServer() as srv:
        drv = ParityDriver(srv.base_url)
        drv.send_cmd("ARM")
        drv.poll_telemetry(1000)

        assert drv.press_direction("F", 40, 1000)
        for _ in range(2):
            time.sleep(drv.hold_repeat_ms / 1000.0)
            v, w = drv.compute_twist(40, "F")
            code, body = drv.send_cmd(drv.format_v_cmd(v, w))
            assert code == 200 and body["status"] == "SENT"
            drv.seq += 1
        drv.release_direction()

        vals = [c["val"] for c in srv.state.cmd_log]
        assert vals.count("V 0.153 0.000") >= 3
        assert vals[-1] == "V 0.000 0.000"


def test_all_8_zones_under_40cm_block_and_mid_motion_auto_halts() -> None:
    zone_pairs = [
        ("F", "front"),
        ("B", "back"),
        ("L", "left"),
        ("R", "right"),
        ("FL", "front_left"),
        ("FR", "front_right"),
        ("BL", "back_left"),
        ("BR", "back_right"),
    ]
    with MockAmrServer() as srv:
        drv = ParityDriver(srv.base_url)
        drv.send_cmd("ARM")

        for direction, zone_key in zone_pairs:
            with srv.state.lock:
                for k in srv.state.zones:
                    srv.state.zones[k] = 150.0
                srv.state.zones[zone_key] = 39.9
            drv.poll_telemetry(1000)
            assert not drv.press_direction(direction, 40, 1000), f"{direction} should block at 39.9 cm"

            with srv.state.lock:
                srv.state.zones[zone_key] = 40.0
            drv.poll_telemetry(1100)
            assert drv.press_direction(direction, 40, 1100), f"{direction} should allow at 40.0 cm"
            drv.release_direction()

        # Mid-motion obstacle entry auto-halt
        with srv.state.lock:
            srv.state.zones["front"] = 120.0
        drv.poll_telemetry(1200)
        assert drv.press_direction("F", 40, 1200)
        assert drv.active_dir == "F"

        with srv.state.lock:
            srv.state.zones["front"] = 35.0
        drv.poll_telemetry(1600)
        assert drv.active_dir is None
        assert srv.state.cmd_log[-1]["val"] == "V 0.000 0.000"


def test_watchdog_and_lidar_health_gates() -> None:
    with MockAmrServer() as srv:
        drv = ParityDriver(srv.base_url)
        drv.send_cmd("ARM")
        drv.poll_telemetry(1000)

        assert drv.can_drive("F", 2500)  # exactly 1500 ms elapsed
        assert not drv.can_drive("F", 2501)  # > 1500 ms watchdog expired

        with srv.state.lock:
            srv.state.lidar_age_s = 0.50
        drv.poll_telemetry(3000)
        assert not drv.can_drive("F", 3000)

        with srv.state.lock:
            srv.state.lidar_age_s = 0.10
            srv.state.lidar_connected = False
        drv.poll_telemetry(3400)
        assert not drv.can_drive("F", 3400)


def test_map_bounds_auto_config_pid_reset_stream_and_mission() -> None:
    with MockAmrServer() as srv:
        drv = ParityDriver(srv.base_url)

        code_map, raw_map = drv._get("/api/map_full")
        assert code_map == 200
        map_json = json.loads(raw_map.decode("utf-8"))
        assert map_json["w"] * map_json["h"] == len(map_json["data"]) <= 4_000_000

        with srv.state.lock:
            srv.state.oversized_map = True
        _, raw_big = drv._get("/api/map_full")
        big_json = json.loads(raw_big.decode("utf-8"))
        assert big_json["w"] * big_json["h"] > 4_000_000

        assert drv._get("/api/auto_config?speed=65&turn=65")[0] == 200
        assert drv._get("/api/auto_config?speed=150&turn=150")[0] == 400
        assert drv._get("/api/pid?kp=0.5&ki=1.5&kd=0.01")[0] == 200
        assert drv._get("/api/reset_map")[0] == 200

        code_stream, raw_stream = drv._get("/stream")
        assert code_stream == 200
        assert b"\xff\xd8" in raw_stream and b"\xff\xd9" in raw_stream

        code_mission, raw_mission = drv._get("/?compact=1")
        assert code_mission == 200
        assert b"AMR Compact Mission" in raw_mission


def test_mock_server_rejects_malformed_and_nan_velocity() -> None:
    with MockAmrServer() as srv:
        drv = ParityDriver(srv.base_url)
        for bad_cmd in ("V abc def", "V nan 0.000", "V inf 0.000", "V 0.1", "V"):
            q = urllib.parse.quote(bad_cmd, safe="")
            code, raw = drv._get(f"/api/cmd?val={q}&epoch=1&seq=0")
            assert code == 400, f"{bad_cmd} should return 400"
            body = json.loads(raw.decode("utf-8"))
            assert body["status"] == "REJECTED"
            assert body["reason"] == "INVALID_VELOCITY"

        code_tel, raw_tel = drv._get("/api/telemetry")
        assert code_tel == 200

        def _reject_nonfinite(val: str) -> Any:
            raise ValueError(f"Non-RFC JSON constant: {val}")

        tel = json.loads(raw_tel.decode("utf-8"), parse_constant=_reject_nonfinite)
        assert tel["robot"]["l_rpm"] == 0.0
        assert tel["robot"]["r_rpm"] == 0.0


def test_mock_server_rejects_stale_epoch_velocity() -> None:
    with MockAmrServer() as srv:
        drv = ParityDriver(srv.base_url)
        q_stop = urllib.parse.quote("V 0.000 0.000", safe="")
        code_stop, raw_stop = drv._get(f"/api/cmd?val={q_stop}&epoch=3&seq=1")
        assert code_stop == 200
        assert json.loads(raw_stop.decode("utf-8"))["status"] == "SENT"

        q_stale = urllib.parse.quote("V 0.249 0.000", safe="")
        code_stale, raw_stale = drv._get(f"/api/cmd?val={q_stale}&epoch=2&seq=0")
        assert code_stale == 409
        body_stale = json.loads(raw_stale.decode("utf-8"))
        assert body_stale["status"] == "REJECTED"
        assert body_stale["reason"] == "STALE_EPOCH"

        _, raw_tel = drv._get("/api/telemetry")
        tel = json.loads(raw_tel.decode("utf-8"))
        assert tel["robot"]["l_rpm"] == 0.0
        assert tel["robot"]["r_rpm"] == 0.0


def test_parity_driver_fails_closed_on_non_finite_zones() -> None:
    with MockAmrServer() as srv:
        drv = ParityDriver(srv.base_url)
        drv.send_cmd("ARM")
        drv.poll_telemetry(1000)
        assert drv.can_drive("F", 1000)

        drv.latest_telemetry["zones"]["front"] = float("inf")
        assert not drv.can_drive("F", 1000)

        drv.latest_telemetry["zones"]["front"] = float("nan")
        assert not drv.can_drive("F", 1000)
