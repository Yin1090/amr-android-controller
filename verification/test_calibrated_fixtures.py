"""Tests validating Topic 2 calibrated sample fixtures, parity driver behavior, and bilingual docs."""

from __future__ import annotations

import csv
import json
import math
import statistics
import sys
import urllib.request
from pathlib import Path

VERIFICATION_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = VERIFICATION_DIR.parent
SAMPLES_DIR = PROJECT_ROOT / "samples"
if str(VERIFICATION_DIR) not in sys.path:
    sys.path.insert(0, str(VERIFICATION_DIR))

from mock_amr_server import MockAmrServer  # noqa: E402
from test_amr_protocol_and_interlock import ParityDriver  # noqa: E402


def test_hardware_profile_fixture_matches_topic2_specs() -> None:
    profile = json.loads((SAMPLES_DIR / "amr_hardware_profile.json").read_text(encoding="utf-8"))
    kin = profile["kinematics"]
    assert kin["wheel_radius_m"] == 0.0366
    assert kin["report_track_width_m"] == 0.1875
    assert kin["report_half_track_m"] == 0.09375
    assert kin["client_half_track_m"] == 0.096
    assert kin["min_rpm"] == 40
    assert kin["max_rpm"] == 135

    sbc = profile["onboard_compute"]
    assert sbc["os_ros"] == "ROS 2 Jazzy"
    assert set(sbc["ros_packages"]) == {
        "rplidar_ros2",
        "slam_toolbox",
        "tf2 static_transform_publisher",
    }
    assert sbc["services"]["rest_api_port"] == 8080
    assert sbc["services"]["mjpeg_camera_port"] == 8081
    assert sbc["services"]["auth_proxy_port"] == 8090

    mcu = profile["mcu_pinout"]
    assert mcu["serial_bridge"] == "CH340"
    assert mcu["baud_rate"] == 115200
    assert mcu["motor_driver"]["i2c_address"] == "0x42"
    assert mcu["motor_driver"]["sda_gpio"] == 21
    assert mcu["motor_driver"]["scl_gpio"] == 22
    assert mcu["encoders"]["ppr"] == 937
    assert mcu["encoders"]["left_gpio"] == [18, 19]
    assert mcu["encoders"]["right_gpio"] == [25, 26]
    assert mcu["lidar"]["model"] == "RPLiDAR C1"
    assert mcu["lidar"]["scan_rate_hz"] == 10.0

    timers = profile["safety_and_timers"]["client_timers_ms"]
    assert timers == {
        "control_hold_repeat": 150,
        "watchdog_health": 250,
        "telemetry_poll": 400,
        "map_poll": 500,
        "map_full_poll": 1000,
    }


def test_telemetry_latency_n40_statistics_match_benchmark() -> None:
    with (SAMPLES_DIR / "telemetry_latency_n40.csv").open("r", encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))
    assert len(rows) == 40
    latencies = [float(r["latency_ms"]) for r in rows]
    assert all(r["http_status"] == "200" and r["via_proxy"] == "true" for r in rows)

    s = sorted(latencies)
    p95_nr = s[math.ceil(0.95 * len(s)) - 1]
    assert min(s) == 26.4
    assert statistics.median(s) == 36.0
    assert round(statistics.mean(s), 1) == 40.9
    assert p95_nr == 60.3
    assert p95_nr < 150.0
    assert max(s) == 163.9


def test_motor_tracking_benchmarks_and_interlock_semantics() -> None:
    with (SAMPLES_DIR / "motor_tracking_benchmarks.csv").open("r", encoding="utf-8", newline="") as fh:
        rows = {r["scenario"]: r for r in csv.DictReader(fh)}

    assert len(rows) == 6
    assert float(rows["unarmed_40rpm"]["left_rpm_actual"]) == 0.0
    assert float(rows["unarmed_40rpm"]["right_rpm_actual"]) == 0.0

    assert float(rows["forward_40rpm"]["left_rpm_actual"]) == 46.09
    assert float(rows["forward_40rpm"]["right_rpm_actual"]) == 47.38

    assert float(rows["reverse_40rpm"]["left_rpm_actual"]) == -38.41
    assert float(rows["reverse_40rpm"]["right_rpm_actual"]) == -38.41

    l_135 = float(rows["max_135rpm"]["left_rpm_actual"])
    r_135 = float(rows["max_135rpm"]["right_rpm_actual"])
    assert abs(l_135 - 135.0) / 135.0 < 0.03
    assert abs(r_135 - 135.0) / 135.0 < 0.03

    lock = rows["obstacle_lock_135rpm"]
    assert float(lock["lidar_min_m"]) == 0.17
    assert float(lock["left_rpm_actual"]) == 0.0
    assert float(lock["right_rpm_actual"]) == 0.0
    assert lock["expected_motion"] == "INTERLOCK_ZERO"


def test_slam_map_101x99_snapshot_schema_and_metrics() -> None:
    snap = json.loads((SAMPLES_DIR / "slam_map_101x99_snapshot.json").read_text(encoding="utf-8"))
    assert snap["w"] == 101
    assert snap["h"] == 99
    assert snap["res"] == 0.05
    assert len(snap["data"]) == 101 * 99
    assert set(snap["data"]).issubset({-1, 0, 100})
    assert 0 in snap["data"] and 100 in snap["data"]

    metrics = snap["snapshot_metrics"]
    assert metrics["scan_rate_hz"] == 10.0
    assert metrics["points_per_rev"] == 674
    assert metrics["age_s"] == 0.018
    assert metrics["nearest_obstacle_m"] == 0.62
    assert metrics["nearest_obstacle_deg"] == 268

    pose = metrics["mission_pose"]
    assert pose["x_m"] == 0.114
    assert pose["y_m"] == -2.120
    assert pose["heading_deg"] == 124.6
    assert pose["distance_m"] == 7.88
    assert pose["coverage_pct"] == 3.7
    assert pose["coverage_pts"] == 108
    assert pose["scan_arc_deg"] == 347
    assert pose["scan_pts"] == 674


def test_telemetry_samples_with_mock_server_and_parity_driver() -> None:
    samples = json.loads((SAMPLES_DIR / "telemetry_samples.json").read_text(encoding="utf-8"))
    assert set(samples.keys()) == {"nominal_safe", "turning_warning", "obstacle_lock_danger"}

    with MockAmrServer() as srv:
        drv = ParityDriver(srv.base_url)
        code, body = drv.send_cmd("ARM")
        assert code == 200 and body["status"] == "SENT"

        now_ms = 1_000
        # 1. Nominal safe sample: all 8 zones >= 62 cm -> forward drive allowed
        with srv.state.lock:
            srv.state.zones = dict(samples["nominal_safe"]["zones"])
            srv.state.lidar_age_s = samples["nominal_safe"]["lidar"]["age_s"]
        code, telem = drv.poll_telemetry(now_ms)
        assert code == 200 and telem is not None
        assert drv.can_drive("F", now_ms) is True
        assert drv.press_direction("F", 40, now_ms) is True
        drv.release_direction()

        # 2. Turning warning sample: front_left = 29 cm -> FL blocked, F allowed
        now_ms += 200
        with srv.state.lock:
            srv.state.zones = dict(samples["turning_warning"]["zones"])
        code, _ = drv.poll_telemetry(now_ms)
        assert code == 200
        assert drv.can_drive("FL", now_ms) is False
        assert drv.can_drive("F", now_ms) is True

        # 3. Obstacle lock danger sample: front = 17 cm -> F blocked, B recovery allowed
        now_ms += 200
        with srv.state.lock:
            srv.state.zones = dict(samples["obstacle_lock_danger"]["zones"])
        code, _ = drv.poll_telemetry(now_ms)
        assert code == 200
        assert drv.can_drive("F", now_ms) is False
        assert drv.can_drive("B", now_ms) is True

        # Verify /api/map_full wire compatibility against slam_map_101x99_snapshot.json
        with urllib.request.urlopen(f"{srv.base_url}/api/map_full", timeout=2.0) as resp:
            wire_map = json.loads(resp.read().decode("utf-8"))
        snap = json.loads((SAMPLES_DIR / "slam_map_101x99_snapshot.json").read_text(encoding="utf-8"))
        for key in ("w", "h", "res", "ox", "oy", "yaw", "data"):
            assert key in wire_map and key in snap


def test_readme_is_bilingual_english_and_vietnamese() -> None:
    readme = (PROJECT_ROOT / "README.md").read_text(encoding="utf-8")
    for required_en in [
        "Overview",
        "Architecture",
        "Build & Verification",
        "./gradlew testDebugUnitTest",
        "./gradlew assembleDebug",
        "pytest verification/",
    ]:
        assert required_en in readme, f"Missing English README token: {required_en}"

    for required_vi in [
        "Tổng quan",
        "Kiến trúc",
        "Bộ dữ liệu",
        "Cài đặt",
        "Kiểm thử tự động",
    ]:
        assert required_vi in readme, f"Missing Vietnamese README section: {required_vi}"
