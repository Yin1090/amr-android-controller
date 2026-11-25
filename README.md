# AMR Android Controller

Package: `com.yin1090.amrcontroller`

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-2024.10.01-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![ROS 2 Jazzy](https://img.shields.io/badge/ROS%202-Jazzy-22314E.svg)](https://docs.ros.org/en/jazzy/)

> Bilingual portfolio repository (**English** & **Tiếng Việt**) — Native Kotlin/Jetpack Compose mobile cockpit for a Differential-Drive 2-Wheel Autonomous Mobile Robot (AMR), calibrated against experimental telemetry, motor-tracking, and SLAM benchmarks.

---

## English Documentation

### Overview

`amr-android-controller` is a 3-screen tactical Android controller built with **Kotlin 2.0.21** and **Jetpack Compose** for operating a Differential-Drive 2-Wheel AMR over HTTP REST (`:8080`), MJPEG optical streaming (`:8081`), and authenticated proxy routing (`:8090`). It includes:

- **Screen 1 (`ConnectionScreen`)**: Configurable host (`127.0.0.1` or `amr.local` placeholder), API/Camera ports (`8080`/`8081`), `12 s` `/api/telemetry` probe, and built-in **OFFLINE DEMO** mode.
- **Screen 2 (`PreflightScreen`)**: 3-step readiness gate (`Control API`, `LiDAR & Map`, `Camera Feed`) with `1000 ms` ticks (`0/3 -> 3/3`).
- **Screen 3 (`CockpitScreen`)**: 3-column landscape dark tactical cockpit (`LidarMapCanvas` + `HoldDriveDPad`, `MjpegHudView` + `TelemetryGrid`, `ControlRightColumn` with `40–135 RPM` stepper and `EMERGENCY STOP`), plus `MissionOverlay` (`/?compact=1`) and armed-guarded `SettingsOverlay`.

### Architecture, Hardware Stack & Kinematics

#### Onboard Hardware & ROS 2 Jazzy Stack

| Subsystem | Hardware / Software Component | Interface / Parameters |
|---|---|---|
| Onboard SBC | **Raspberry Pi 4** (`ROS 2 Jazzy`) | `rplidar_ros2`, `slam_toolbox`, `tf2 static_transform_publisher` |
| Network Services | REST API / MJPEG Camera / Auth Proxy | `:8080` REST, `:8081` MJPEG (`640×480`), `:8090` Auth Proxy |
| MCU Bridge | **ESP32** via USB Serial (`CH340`) | `115200` baud |
| Motor Driver | **MKE-M18** Dual DC Driver (I2C) | `GPIO21` (SDA), `GPIO22` (SCL), I2C address `0x42` |
| Wheel Encoders | Quadrature Encoders (`937` PPR) | Left: `GPIO18` / `GPIO19`, Right: `GPIO25` / `GPIO26` |
| 2D LiDAR | **RPLiDAR C1** | `10.0 Hz`, `666–682` pts/rev |

#### Kinematics, Safety Interlock & Timers

- **Wheel & Chassis Geometry**:
  - Wheel radius $R = 0.0366\text{ m}$, conversion factor $K = \frac{2\pi R}{60} \approx 0.003833\text{ m/s/RPM}$ ($40\text{ RPM} \approx 0.153\text{ m/s}$).
  - Physical bench track width $L = 0.1875\text{ m}$ ($B = 0.09375\text{ m}$); Android client `Kinematics.kt` uses calibrated half-track $B = 0.096\text{ m}$ (`HALF_TRACK_M = 0.096`, preserving JUnit4 & simulator parity).
  - Speed range: `40–135 RPM` (`10 RPM` steps), $\omega = \min(1.80, 0.60 \cdot |v| / B)$, diagonal factor $1/\sqrt{2} \approx 0.707$, formatted with `Locale.US` (`V %.3f %.3f`).
- **8-Zone Active Safety Interlock & FSM**:
  - 8 directional zones (`front`, `back`, `left`, `right`, `front_left`, `front_right`, `back_left`, `back_right`, mapped from LiDAR sectors `zF..zBR`) block motion whenever the target zone $< 40.0\text{ cm}$ (`0.40 m`), and auto-halt mid-motion (`V 0.000 0.000`).
  - LiDAR freshness gate: `connected == true` and `age_s < 0.5 s`.
  - `ARM`/`STOP` FSM: duplicate `ARM` when armed returns `HTTP 422 FAILED, PREFLIGHT_NOT_READY`; `STOP` returns `HTTP 200 SENT, STOP_CONFIRMED` (`armed=false, latched=true`).
  - Polling & Watchdog Timers: `150 ms` hold repeat (`ClockControl`), `250 ms` watchdog/health (`ClockHealth`), `400 ms` telemetry (`ClockTelemetry`), `1000 ms` full occupancy map polling (`/api/map_full`, `ClockMapFull`; reference stack also notes `500 ms` `ClockMap`), `1500 ms` telemetry watchdog timeout.

### Calibrated Sample Datasets (`samples/`)

| File | Description | Key Experimental Metrics |
|---|---|---|
| `samples/amr_hardware_profile.json` | Sanitized Pi 4 / ESP32 / ROS 2 / timer spec | `R=0.0366 m`, `L=0.1875 m` (`B_client=0.096 m`), `150/250/400/500/1000 ms` |
| `samples/telemetry_latency_n40.csv` | `n = 40` proxy telemetry round-trip samples | Min `26.4`, Median `36.0`, Mean `40.9`, **p95 `60.3 ms` (`< 150 ms`)**, Max `163.9 ms` |
| `samples/motor_tracking_benchmarks.csv` | 6 closed-loop motor & interlock benchmarks | Unarmed `0/0`; `40` Fwd `46.09/47.38`; `80` Turn `74.26/43.53`; `40` Rev `-38.41/-38.41`; `135` Max `133.16/138.28` (`< 3%` err); `135 @ 0.17 m` Lock `0/0` |
| `samples/slam_map_101x99_snapshot.json` | `/api/map_full` `101×99` occupancy snapshot | `0.05 m/cell` (`9999` cells), `age_s=0.018`, `674` pts (`347/360°`), nearest `0.62 m @ 268°`, pose `(0.114, -2.120, 124.6°)`, `dist=7.88 m`, `cov=3.7% (108 pts)` |
| `samples/telemetry_samples.json` | Valid `/api/telemetry` payloads (cm zones) | `nominal_safe`, `turning_warning`, `obstacle_lock_danger` |

### Installation & Usage

1. **Android Studio / Gradle** (requires **JDK 17** and **Android SDK 35**):
   - Open `amr-android-controller` in Android Studio or run Gradle on a JDK 17 + Android SDK environment.
   - Launch on Android 8.0+ (`minSdk 26`), tap **OFFLINE DEMO** to test without hardware, or connect to a loopback/live AMR REST server on `127.0.0.1:8080` (`8081` camera).
2. **Host Python Verification** (Python 3.10+ & `pytest`):
   - Inspect or validate calibrated fixtures under `samples/` against `verification/mock_amr_server.py`.

### Build & Verification

On a workstation with **JDK 17 (`JAVA_HOME`)** and **Android SDK (`ANDROID_HOME`)** installed, open the project in Android Studio or generate the Gradle Wrapper (`gradle wrapper`, configured for Gradle `8.9` in `gradle/wrapper/gradle-wrapper.properties`) and run:

```bash
./gradlew testDebugUnitTest   # Runs 51 JUnit4 unit tests across Kinematics, SafetyInterlock, AmrApiClient, CockpitStateMachine, MjpegBackoff
./gradlew assembleDebug       # Compiles Android debug APK
```

On hosts without JDK/Android SDK (including this Windows verification host), the full Python host verification suite runs offline and verifies Kotlin delimiters, package contracts, privacy sanitization, loopback HTTP parity, and calibrated sample datasets:

```bash
pytest verification/ -v
python verification/verify_kotlin_project.py --json
```

---

## Tài liệu Tiếng Việt

### Tổng quan

`amr-android-controller` là ứng dụng điều khiển Android viết bằng **Kotlin 2.0.21** và **Jetpack Compose** theo mô hình 3 màn hình (Kết nối -> Kiểm tra tiền bay `Preflight` -> Buồng lái ngang `Cockpit`) dùng để điều khiển Robot tự hành vi sai 2 bánh (AMR) qua HTTP REST (`:8080`), luồng camera MJPEG (`:8081`) và cổng xác thực (`:8090`), kèm chế độ **OFFLINE DEMO** chạy độc lập không cần phần cứng thật.

### Kiến trúc, Phần cứng & Động học

- **Khối tính toán trên robot**: **Raspberry Pi 4** chạy **ROS 2 Jazzy** (`rplidar_ros2`, `slam_toolbox`, `tf2 static_transform_publisher`), cung cấp REST `:8080`, MJPEG `:8081` (`640×480`) và Auth Proxy `:8090`.
- **Vi điều khiển & Sơ đồ chân ESP32**: Kết nối qua USB Serial `CH340` (`115200` baud); mạch công suất **MKE-M18** giao tiếp I2C (`GPIO21` SDA, `GPIO22` SCL, địa chỉ `0x42`); encoder trái (`GPIO18`/`GPIO19`, `937` PPR), encoder phải (`GPIO25`/`GPIO26`, `937` PPR); cảm biến **RPLiDAR C1** (`10.0 Hz`, `666–682` điểm/vòng).
- **Thông số động học & Khóa an toàn 8 vùng**:
  - Bán kính bánh xe $R = 0.0366\text{ m}$ ($1\text{ RPM} \approx 0.003833\text{ m/s}$, `40–135 RPM` bước `10 RPM`), bề rộng cơ sở thực nghiệm $L = 0.1875\text{ m}$ ($B = 0.09375\text{ m}$; ứng dụng Android giữ $B = 0.096\text{ m}$ để tương thích hợp đồng JUnit4 & trình mô phỏng).
  - Khóa an toàn chủ động 8 vùng `< 40.0 cm` (`< 0.40 m`), kiểm tra độ tươi LiDAR (`age_s < 0.5 s`), watchdog `1500 ms`, chu kỳ polling `150 / 250 / 400 / 500 / 1000 ms`, và máy trạng thái `ARM`/`STOP` (`HTTP 422 FAILED, PREFLIGHT_NOT_READY` / `HTTP 200 SENT, STOP_CONFIRMED`).

### Bộ dữ liệu mẫu hiệu chuẩn (`samples/`)

- `samples/amr_hardware_profile.json`: Hồ sơ phần cứng, chân GPIO, ROS 2 Jazzy và chu kỳ timer chuẩn hóa.
- `samples/telemetry_latency_n40.csv`: `40` mẫu độ trễ qua proxy (`Min = 26.4 ms`, `Median = 36.0 ms`, `Mean = 40.9 ms`, `p95 = 60.3 ms < 150 ms`, `Max = 163.9 ms`).
- `samples/motor_tracking_benchmarks.csv`: `6` kịch bản bám tốc độ động cơ (`40–135 RPM`, sai số ở `135 RPM < 3%`, khóa an toàn `0.17 m -> 0/0 RPM`).
- `samples/slam_map_101x99_snapshot.json`: Bản đồ SLAM `101×99` ô (`0.05 m/ô`, `9999` ô, `age_s = 0.018 s`, vật cản gần nhất `0.62 m @ 268°`, pose `X=0.114 m, Y=-2.120 m, Heading=124.6°, Distance=7.88 m, coverage=3.7% (108 pts), scan=347/360° | 674 pts | 0.04s`).
- `samples/telemetry_samples.json`: Các gói `/api/telemetry` chuẩn hóa (`nominal_safe`, `turning_warning`, `obstacle_lock_danger`).

### Cài đặt & Sử dụng

1. Mở dự án bằng **Android Studio** (yêu cầu **JDK 17** và **Android SDK 35**) để build APK hoặc chạy kiểm thử JUnit4.
2. Trên máy host không có Android SDK, sử dụng **Python 3.10+** và `pytest` để chạy bộ kiểm thử giao thức loopback (`mock_amr_server.py`) và bộ dữ liệu `samples/`.

### Kiểm thử tự động

```bash
# Kiểm thử JUnit4 & build APK (mở bằng Android Studio hoặc chạy `gradle wrapper` trước khi gọi `./gradlew` trên môi trường có JDK 17 + Android SDK)
./gradlew testDebugUnitTest
./gradlew assembleDebug

# Kiểm thử Python trên máy host (offline, không cần phần cứng hoặc Android SDK)
python -m pytest verification -v
python verification/verify_kotlin_project.py --json
```
