#!/usr/bin/env python3
"""Host-runnable structural, lexical, contract, and privacy verifier for amr-android-controller."""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

PROJECT_ROOT = Path(__file__).resolve().parent.parent

REQUIRED_FILES = [
    "README.md",
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "gradle/wrapper/gradle-wrapper.properties",
    "app/build.gradle.kts",
    "app/proguard-rules.pro",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/res/xml/network_security_config.xml",
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values/themes.xml",
    "app/src/main/java/com/yin1090/amrcontroller/MainActivity.kt",
    "app/src/main/java/com/yin1090/amrcontroller/AmrApp.kt",
    "app/src/main/java/com/yin1090/amrcontroller/model/AmrModels.kt",
    "app/src/main/java/com/yin1090/amrcontroller/net/HttpTransport.kt",
    "app/src/main/java/com/yin1090/amrcontroller/net/AmrApiClient.kt",
    "app/src/main/java/com/yin1090/amrcontroller/domain/Kinematics.kt",
    "app/src/main/java/com/yin1090/amrcontroller/domain/SafetyInterlock.kt",
    "app/src/main/java/com/yin1090/amrcontroller/state/ClockProvider.kt",
    "app/src/main/java/com/yin1090/amrcontroller/state/CockpitStateMachine.kt",
    "app/src/main/java/com/yin1090/amrcontroller/demo/DemoSimulatorEngine.kt",
    "app/src/main/java/com/yin1090/amrcontroller/data/AmrSettingsStore.kt",
    "app/src/main/java/com/yin1090/amrcontroller/data/MjpegBackoff.kt",
    "app/src/main/java/com/yin1090/amrcontroller/data/MjpegStreamClient.kt",
    "app/src/main/java/com/yin1090/amrcontroller/ui/theme/Color.kt",
    "app/src/main/java/com/yin1090/amrcontroller/ui/theme/Type.kt",
    "app/src/main/java/com/yin1090/amrcontroller/ui/theme/Theme.kt",
    "app/src/main/java/com/yin1090/amrcontroller/ui/state/CockpitViewModel.kt",
    "app/src/main/java/com/yin1090/amrcontroller/ui/screens/ConnectionScreen.kt",
    "app/src/main/java/com/yin1090/amrcontroller/ui/screens/PreflightScreen.kt",
    "app/src/main/java/com/yin1090/amrcontroller/ui/screens/CockpitScreen.kt",
    "app/src/main/java/com/yin1090/amrcontroller/ui/components/LidarMapCanvas.kt",
    "app/src/main/java/com/yin1090/amrcontroller/ui/components/HoldDriveDPad.kt",
    "app/src/main/java/com/yin1090/amrcontroller/ui/components/MjpegHudView.kt",
    "app/src/main/java/com/yin1090/amrcontroller/ui/components/TelemetryGrid.kt",
    "app/src/main/java/com/yin1090/amrcontroller/ui/components/ControlRightColumn.kt",
    "app/src/main/java/com/yin1090/amrcontroller/ui/components/MissionOverlay.kt",
    "app/src/main/java/com/yin1090/amrcontroller/ui/components/SettingsOverlay.kt",
    "app/src/test/java/com/yin1090/amrcontroller/domain/KinematicsTest.kt",
    "app/src/test/java/com/yin1090/amrcontroller/domain/SafetyInterlockTest.kt",
    "app/src/test/java/com/yin1090/amrcontroller/state/CockpitStateMachineTest.kt",
    "app/src/test/java/com/yin1090/amrcontroller/net/AmrApiClientTest.kt",
    "app/src/test/java/com/yin1090/amrcontroller/data/MjpegBackoffTest.kt",
]

PURE_JVM_FILES = [
    "app/src/main/java/com/yin1090/amrcontroller/model/AmrModels.kt",
    "app/src/main/java/com/yin1090/amrcontroller/net/HttpTransport.kt",
    "app/src/main/java/com/yin1090/amrcontroller/net/AmrApiClient.kt",
    "app/src/main/java/com/yin1090/amrcontroller/domain/Kinematics.kt",
    "app/src/main/java/com/yin1090/amrcontroller/domain/SafetyInterlock.kt",
    "app/src/main/java/com/yin1090/amrcontroller/state/ClockProvider.kt",
    "app/src/main/java/com/yin1090/amrcontroller/state/CockpitStateMachine.kt",
    "app/src/main/java/com/yin1090/amrcontroller/demo/DemoSimulatorEngine.kt",
    "app/src/main/java/com/yin1090/amrcontroller/data/MjpegBackoff.kt",
]

XML_FILES = [
    "app/src/main/AndroidManifest.xml",
    "app/src/main/res/xml/network_security_config.xml",
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values/themes.xml",
]


def check_kotlin_delimiters(source: str, filename: str = "<input>") -> list[str]:
    """String- and comment-aware delimiter balance checker for .kt and .kts files."""
    errors: list[str] = []
    n = len(source)
    i = 0
    line = 1
    col = 1

    # Stack items: (char, line, col, kind) where kind in {"code", "template"}
    stack: list[tuple[str, int, int, str]] = []
    matching = {"{": "}", "(": ")", "[": "]"}
    closing_to_open = {"}": "{", ")": "(", "]": "["}

    # Mode stack supports nested ${ ... } expressions inside strings
    # Mode entries: ("code",) | ("str",) | ("raw_str",)
    modes: list[str] = ["code"]
    block_comment_depth = 0

    def advance(step: int = 1) -> None:
        nonlocal i, line, col
        for _ in range(step):
            if i < n:
                if source[i] == "\n":
                    line += 1
                    col = 1
                else:
                    col += 1
                i += 1

    while i < n:
        ch = source[i]
        nxt = source[i + 1] if i + 1 < n else ""

        if block_comment_depth > 0:
            if ch == "/" and nxt == "*":
                block_comment_depth += 1
                advance(2)
            elif ch == "*" and nxt == "/":
                block_comment_depth -= 1
                advance(2)
            else:
                advance(1)
            continue

        mode = modes[-1]

        if mode == "str":
            if ch == "\\":
                advance(2 if i + 1 < n else 1)
            elif ch == "$" and nxt == "{":
                stack.append(("{", line, col, "template"))
                modes.append("code")
                advance(2)
            elif ch == '"':
                modes.pop()
                advance(1)
            elif ch == "\n":
                errors.append(f"{filename}:{line}:{col}: unterminated string literal")
                modes.pop()
                advance(1)
            else:
                advance(1)
            continue

        if mode == "raw_str":
            if ch == "$" and nxt == "{":
                stack.append(("{", line, col, "template"))
                modes.append("code")
                advance(2)
            elif source.startswith('"""', i):
                # Consume any extra quotes at the end of raw string (e.g. """"abc""""")
                while i + 3 < n and source[i + 3] == '"':
                    advance(1)
                modes.pop()
                advance(3)
            else:
                advance(1)
            continue

        # mode == "code"
        if ch == "/" and nxt == "/":
            while i < n and source[i] != "\n":
                advance(1)
            continue
        if ch == "/" and nxt == "*":
            block_comment_depth = 1
            advance(2)
            continue
        if source.startswith('"""', i):
            modes.append("raw_str")
            advance(3)
            continue
        if ch == '"':
            modes.append("str")
            advance(1)
            continue
        if ch == "'":
            start_line, start_col = line, col
            advance(1)
            if i < n and source[i] == "\\":
                advance(2 if i + 1 < n else 1)
            elif i < n and source[i] != "\n":
                advance(1)
            if i >= n or source[i] != "'":
                errors.append(f"{filename}:{start_line}:{start_col}: unterminated char literal")
            else:
                advance(1)
            continue

        if ch in matching:
            stack.append((ch, line, col, "code"))
            advance(1)
        elif ch in closing_to_open:
            if not stack:
                errors.append(f"{filename}:{line}:{col}: unexpected '{ch}' with empty stack")
            else:
                open_ch, o_line, o_col, kind = stack.pop()
                expected = matching[open_ch]
                if ch != expected:
                    errors.append(
                        f"{filename}:{line}:{col}: mismatched '{ch}', expected '{expected}' for '{open_ch}' from {o_line}:{o_col}"
                    )
                elif kind == "template":
                    # Closing '}' of ${...} returns to enclosing string mode
                    if len(modes) > 1:
                        modes.pop()
            advance(1)
        else:
            advance(1)

    if block_comment_depth > 0:
        errors.append(f"{filename}: unterminated block comment (depth={block_comment_depth})")
    if len(modes) > 1:
        errors.append(f"{filename}: unterminated string literal at EOF (mode={modes[-1]})")
    for open_ch, o_line, o_col, _ in stack:
        errors.append(f"{filename}:{o_line}:{o_col}: unclosed '{open_ch}'")

    return errors


def _build_privacy_patterns() -> list[tuple[str, re.Pattern[str]]]:
    """Construct generic security and sanitization regex checks (zero PII literals)."""
    raw_specs = [
        ("private_ip_192", r"\b19[2]\.168\.\d{1,3}\.\d{1,3}\b"),
        ("private_ip_10", r"\b1[0]\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"),
        ("private_ip_172", r"\b17[2]\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}\b"),
        ("consumer_email", r"[A-Za-z0-9._%+-]+@(?:[g]mail|[y]ahoo|[o]utlook|[h]otmail)\.[A-Za-z]{2,}"),
        ("bot_token", r"\b\d{8,10}:[A-Za-z0-9_-]{35}\b"),
        ("github_token", r"\b(?:[g]hp_[A-Za-z0-9]{20,}|[g]ithub_pat_[A-Za-z0-9_]{20,})\b"),
        ("oauth_secret", r"[c]lient_secret"),
        ("credential_assignment", r"\b(?:[w]ifi_pass|[s]sid|[p]sk|[p]assword)\s*=\s*[\"'][^\"']+[\"']"),
        ("forbidden_legacy_ext", r"\.(?:a[i]a|b[k]y|s[c]m)\b"),
    ]
    return [(label, re.compile(pattern, re.IGNORECASE)) for label, pattern in raw_specs]


def run_all_checks(root: Path = PROJECT_ROOT) -> dict[str, Any]:
    errors: list[str] = []
    warnings: list[str] = []

    # 1. Required files non-empty
    missing_or_empty: list[str] = []
    for rel in REQUIRED_FILES:
        p = root / rel
        if not p.is_file() or p.stat().st_size == 0:
            missing_or_empty.append(rel)
    if missing_or_empty:
        errors.append(f"Missing or empty required files: {missing_or_empty}")

    # 2. XML parsing and Manifest checks
    for rel in XML_FILES:
        p = root / rel
        if p.is_file():
            try:
                ET.parse(p)
            except ET.ParseError as exc:
                errors.append(f"XML parse error in {rel}: {exc}")

    manifest_path = root / "app/src/main/AndroidManifest.xml"
    if manifest_path.is_file():
        manifest_text = manifest_path.read_text(encoding="utf-8")
        for token in [
            "android.permission.INTERNET",
            ".MainActivity",
            'usesCleartextTraffic="true"',
            "network_security_config",
        ]:
            if token not in manifest_text:
                errors.append(f"AndroidManifest.xml missing required attribute/entry: {token}")

    # 3. Kotlin & Gradle delimiter balance, package-to-path, stubs, Locale.US, pure-JVM boundary
    kt_files = sorted(list(root.rglob("*.kt")) + list(root.rglob("*.kts")))
    delimiter_checked = 0
    for kt in kt_files:
        rel = kt.relative_to(root).as_posix()
        text = kt.read_text(encoding="utf-8")
        delim_errs = check_kotlin_delimiters(text, rel)
        errors.extend(delim_errs)
        delimiter_checked += 1

        if kt.suffix == ".kt":
            # Package-to-path verification
            for base_prefix in ("app/src/main/java/", "app/src/test/java/"):
                if rel.startswith(base_prefix):
                    pkg_rel = rel[len(base_prefix):]
                    expected_pkg = pkg_rel.rsplit("/", 1)[0].replace("/", ".")
                    pkg_match = re.search(r"^\s*package\s+([A-Za-z0-9_.]+)", text, re.MULTILINE)
                    if not pkg_match:
                        errors.append(f"{rel}: missing package declaration")
                    elif pkg_match.group(1) != expected_pkg:
                        errors.append(
                            f"{rel}: package '{pkg_match.group(1)}' does not match path '{expected_pkg}'"
                        )

            # Zero stubs / placeholders
            for stub_pat in [r"\bTODO\s*\(", r"//\s*TODO\b", r"\bFIXME\b", r"\bNotImplementedError\b"]:
                if re.search(stub_pat, text):
                    errors.append(f"{rel}: forbidden stub/placeholder pattern '{stub_pat}'")

            # String.format must specify Locale.US or Locale.ROOT
            for m in re.finditer(r"String\.format\s*\(", text):
                snippet = text[m.start():m.start() + 90]
                if "Locale.US" not in snippet and "Locale.ROOT" not in snippet:
                    errors.append(f"{rel}: String.format without Locale.US/Locale.ROOT near: {snippet[:50]}")

    for rel in PURE_JVM_FILES:
        p = root / rel
        if p.is_file():
            text = p.read_text(encoding="utf-8")
            if re.search(r"^\s*import\s+(android|androidx)\.", text, re.MULTILINE):
                errors.append(f"{rel}: pure-JVM layer must not import android.* or androidx.*")

    # 4. Contract constants, endpoints, timers, 8-zone interlock & JUnit4 coverage
    prod_sources = "\n".join(
        p.read_text(encoding="utf-8")
        for p in sorted((root / "app/src/main/java").rglob("*.kt"))
    )
    required_prod_tokens = [
        "0.0366",
        "0.096",
        "40",
        "135",
        "1.80",
        "0.707",
        "0.60",
        "400L",
        "1_000L",
        "150L",
        "250L",
        "1_500L",
        "2_500L",
        "3_000L",
        "/api/telemetry",
        "/api/map_full",
        "/api/cmd",
        "/api/auto_config",
        "/api/pid",
        "/api/reset_map",
        "/stream",
        "/?compact=1",
        "4_000_000L",
        "front",
        "back",
        "left",
        "right",
        "front_left",
        "front_right",
        "back_left",
        "back_right",
        "40.0",
        "0.5",
        "V 0.000 0.000",
        "CLEAR_FAULT",
        "127.0.0.1 or amr.local",
    ]
    for tok in required_prod_tokens:
        if tok not in prod_sources:
            errors.append(f"Production Kotlin sources missing contract token: '{tok}'")

    test_files = sorted((root / "app/src/test/java").rglob("*.kt"))
    total_tests = 0
    all_test_src = ""
    for tf in test_files:
        txt = tf.read_text(encoding="utf-8")
        all_test_src += "\n" + txt
        count = len(re.findall(r"@Test\b", txt))
        total_tests += count
        if count < 5:
            errors.append(f"{tf.relative_to(root).as_posix()}: expected >= 5 @Test methods, found {count}")
    if total_tests < 38:
        errors.append(f"Expected >= 38 total @Test methods, found {total_tests}")

    for tok in ["Locale", "40.0", "39.9", "1_500L", "0.5", "V 0.000 0.000", "CLEAR_FAULT", "2001"]:
        if tok not in all_test_src:
            errors.append(f"JUnit4 test suite missing coverage token: '{tok}'")

    # 5. R2 Privacy & Sanitization Scan across all files in project
    privacy_patterns = _build_privacy_patterns()
    privacy_hits: list[str] = []
    scanned_files = 0
    for fpath in sorted(root.rglob("*")):
        if not fpath.is_file():
            continue
        if any(part in {".git", ".gradle", "build", "__pycache__", ".pytest_cache"} for part in fpath.parts):
            continue
        if fpath.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp", ".jar", ".apk", ".keystore", ".pyc"}:
            continue
        scanned_files += 1
        content = fpath.read_text(encoding="utf-8", errors="ignore")
        rel = fpath.relative_to(root).as_posix()
        for label, pat in privacy_patterns:
            m = pat.search(content)
            if m:
                privacy_hits.append(f"{rel}: matched {label} ('{m.group(0)}')")

    if privacy_hits:
        errors.extend(privacy_hits)

    # 6. README toolchain transparency check
    readme_path = root / "README.md"
    if readme_path.is_file():
        readme = readme_path.read_text(encoding="utf-8")
        for req_phrase in ["Build & Verification", "./gradlew testDebugUnitTest", "./gradlew assembleDebug", "pytest verification/"]:
            if req_phrase not in readme:
                errors.append(f"README.md missing transparency section/command: '{req_phrase}'")

    return {
        "ok": len(errors) == 0,
        "required_files": len(REQUIRED_FILES),
        "kt_kts_checked": delimiter_checked,
        "junit_test_files": len(test_files),
        "junit_test_methods": total_tests,
        "privacy_files_scanned": scanned_files,
        "privacy_hits": len(privacy_hits),
        "errors": errors,
        "warnings": warnings,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify amr-android-controller Kotlin/Compose project")
    parser.add_argument("--json", action="store_true", help="Emit JSON report")
    args = parser.parse_args()

    result = run_all_checks(PROJECT_ROOT)
    if args.json:
        print(json.dumps(result, indent=2))
    else:
        status = "PASS" if result["ok"] else "FAIL"
        print(
            f"[{status}] files={result['required_files']} kt_kts={result['kt_kts_checked']} "
            f"junit_tests={result['junit_test_methods']} privacy_scanned={result['privacy_files_scanned']} "
            f"errors={len(result['errors'])}"
        )
        for err in result["errors"]:
            print(f"  ERROR: {err}")
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
