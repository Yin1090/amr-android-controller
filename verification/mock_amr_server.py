"""Stateful loopback ThreadingHTTPServer simulating the AMR REST API and MJPEG camera."""

from __future__ import annotations

import json
import math
import threading
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlparse


@dataclass
class MockAmrState:
    lock: threading.Lock = field(default_factory=threading.Lock)
    armed: bool = False
    fault: str | None = None
    lidar_connected: bool = True
    lidar_age_s: float = 0.05
    telemetry_http_status: int = 200
    oversized_map: bool = False
    reset_map_count: int = 0
    latest_epoch: int = 0
    pid_gains: dict[str, float] = field(default_factory=lambda: {"kp": 0.5, "ki": 1.5, "kd": 0.01})
    auto_speed: int = 40
    auto_turn: int = 40
    l_rpm: float = 0.0
    r_rpm: float = 0.0
    zones: dict[str, float] = field(
        default_factory=lambda: {
            "front": 150.0,
            "back": 150.0,
            "left": 150.0,
            "right": 150.0,
            "front_left": 150.0,
            "front_right": 150.0,
            "back_left": 150.0,
            "back_right": 150.0,
        }
    )
    cmd_log: list[dict[str, Any]] = field(default_factory=list)


def _make_handler(state: MockAmrState) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, format: str, *args: Any) -> None:
            return

        def _send_json(self, code: int, payload: dict[str, Any]) -> None:
            raw = json.dumps(payload).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self) -> None:
            parsed = urlparse(self.path)
            path = parsed.path
            qs = parse_qs(parsed.query)

            if path == "/api/telemetry":
                with state.lock:
                    if state.telemetry_http_status != 200:
                        self._send_json(state.telemetry_http_status, {"error": f"INJECTED_{state.telemetry_http_status}"})
                        return
                    min_cm = min(state.zones.values())
                    status = "DANGER_STOP" if min_cm < 40.0 else ("WARNING" if min_cm < 70.0 else "SAFE")
                    payload = {
                        "status": status,
                        "min_m": round(min_cm / 100.0, 3),
                        "min_ang": 0.0,
                        "robot": {
                            "x": 0.0,
                            "y": 0.0,
                            "th": 0.0,
                            "l_rpm": state.l_rpm,
                            "r_rpm": state.r_rpm,
                        },
                        "control": {"armed": state.armed, "fault": state.fault},
                        "lidar": {"connected": state.lidar_connected, "age_s": state.lidar_age_s},
                        "ranges": [round(min_cm / 100.0, 3)] * 360,
                        "map_info": {"robot_pose": [0.0, 0.0, 0.0]},
                        "zones": dict(state.zones),
                    }
                self._send_json(200, payload)
                return

            if path == "/api/map_full":
                with state.lock:
                    if state.oversized_map:
                        payload = {
                            "w": 2001,
                            "h": 2001,
                            "res": 0.05,
                            "ox": 0.0,
                            "oy": 0.0,
                            "yaw": 0.0,
                            "data": [],
                        }
                    else:
                        payload = {
                            "w": 4,
                            "h": 4,
                            "res": 0.05,
                            "ox": -0.1,
                            "oy": -0.1,
                            "yaw": 0.0,
                            "data": [0, 0, 50, 100] * 4,
                        }
                self._send_json(200, payload)
                return

            if path == "/api/cmd":
                val = qs.get("val", [""])[0]
                epoch = qs.get("epoch", ["0"])[0]
                seq = qs.get("seq", ["0"])[0]
                try:
                    epoch_num = int(epoch)
                except ValueError:
                    self._send_json(400, {"status": "REJECTED", "reason": "INVALID_EPOCH"})
                    return
                with state.lock:
                    if val == "ARM":
                        if state.fault:
                            state.cmd_log.append({"val": val, "epoch": epoch, "seq": seq, "ts": time.monotonic()})
                            self._send_json(422, {"status": "REJECTED", "reason": "FAULT_ACTIVE", "blockers": ["FAULT"]})
                            return
                        state.latest_epoch = max(state.latest_epoch, epoch_num)
                        state.armed = True
                        state.cmd_log.append({"val": val, "epoch": epoch, "seq": seq, "ts": time.monotonic()})
                        self._send_json(200, {"status": "SENT"})
                        return
                    if val == "CLEAR_FAULT":
                        state.latest_epoch = max(state.latest_epoch, epoch_num)
                        state.fault = None
                        state.cmd_log.append({"val": val, "epoch": epoch, "seq": seq, "ts": time.monotonic()})
                        self._send_json(200, {"status": "SENT"})
                        return
                    if val in ("DISARM", "STOP"):
                        state.latest_epoch = max(state.latest_epoch, epoch_num)
                        state.armed = False
                        state.l_rpm = 0.0
                        state.r_rpm = 0.0
                        state.cmd_log.append({"val": val, "epoch": epoch, "seq": seq, "ts": time.monotonic()})
                        self._send_json(200, {"status": "SENT"})
                        return
                    if val == "V" or val.startswith("V "):
                        parts = val.split()
                        if len(parts) != 3:
                            self._send_json(400, {"status": "REJECTED", "reason": "INVALID_VELOCITY"})
                            return
                        try:
                            v = float(parts[1])
                            w = float(parts[2])
                        except ValueError:
                            self._send_json(400, {"status": "REJECTED", "reason": "INVALID_VELOCITY"})
                            return
                        if not (math.isfinite(v) and math.isfinite(w)):
                            self._send_json(400, {"status": "REJECTED", "reason": "INVALID_VELOCITY"})
                            return
                        if epoch_num < state.latest_epoch:
                            self._send_json(409, {"status": "REJECTED", "reason": "STALE_EPOCH"})
                            return
                        state.latest_epoch = max(state.latest_epoch, epoch_num)
                        k = 2.0 * math.pi * 0.0366 / 60.0
                        b = 0.096
                        state.l_rpm = round((v - w * b) / k, 2)
                        state.r_rpm = round((v + w * b) / k, 2)
                        state.cmd_log.append({"val": val, "epoch": epoch, "seq": seq, "ts": time.monotonic()})
                        self._send_json(200, {"status": "SENT"})
                        return
                self._send_json(400, {"status": "REJECTED", "reason": "UNKNOWN_CMD"})
                return

            if path == "/api/auto_config":
                try:
                    spd = int(qs.get("speed", ["0"])[0])
                    trn = int(qs.get("turn", ["0"])[0])
                except ValueError:
                    self._send_json(400, {"status": "ERROR", "reason": "INVALID_INT"})
                    return
                if 40 <= spd <= 135 and 40 <= trn <= 135:
                    with state.lock:
                        state.auto_speed = spd
                        state.auto_turn = trn
                    self._send_json(200, {"status": "OK", "speed": spd, "turn": trn})
                else:
                    self._send_json(400, {"status": "ERROR", "reason": "OUT_OF_RANGE"})
                return

            if path == "/api/pid":
                try:
                    kp = float(qs.get("kp", ["0.5"])[0])
                    ki = float(qs.get("ki", ["1.5"])[0])
                    kd = float(qs.get("kd", ["0.01"])[0])
                except ValueError:
                    self._send_json(400, {"status": "ERROR", "reason": "INVALID_FLOAT"})
                    return
                if not (math.isfinite(kp) and math.isfinite(ki) and math.isfinite(kd)) or kp < 0.0 or ki < 0.0 or kd < 0.0:
                    self._send_json(400, {"status": "ERROR", "reason": "INVALID_PID"})
                    return
                with state.lock:
                    state.pid_gains = {"kp": kp, "ki": ki, "kd": kd}
                self._send_json(200, {"status": "OK", "kp": kp, "ki": ki, "kd": kd})
                return

            if path == "/api/reset_map":
                with state.lock:
                    state.reset_map_count += 1
                    cnt = state.reset_map_count
                self._send_json(200, {"status": "OK", "reset_count": cnt})
                return

            if path == "/status":
                self._send_json(200, {"camera": "ok", "width": 640, "height": 480})
                return

            if path == "/stream":
                jpeg_chunk = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00\xff\xd9"
                part = (
                    b"--frame\r\n"
                    b"Content-Type: image/jpeg\r\n"
                    + f"Content-Length: {len(jpeg_chunk)}\r\n\r\n".encode("ascii")
                    + jpeg_chunk
                    + b"\r\n--frame--\r\n"
                )
                self.send_response(200)
                self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
                self.send_header("Content-Length", str(len(part)))
                self.end_headers()
                self.wfile.write(part)
                return

            if path == "/" and qs.get("compact", [""])[0] == "1":
                html = b"<!doctype html><html><body><h1>AMR Compact Mission</h1></body></html>"
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(html)))
                self.end_headers()
                self.wfile.write(html)
                return

            self._send_json(404, {"error": "NOT_FOUND"})

    return Handler


class MockAmrServer:
    def __init__(self) -> None:
        self.state = MockAmrState()
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), _make_handler(self.state))
        self.port: int = self._server.server_address[1]
        self.base_url: str = f"http://127.0.0.1:{self.port}"
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    def __enter__(self) -> "MockAmrServer":
        self._thread.start()
        return self

    def __exit__(self, *args: Any) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=2.0)
