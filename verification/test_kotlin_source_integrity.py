"""Pytest wrapper for Kotlin/Gradle/XML integrity, delimiter lexer, and R2 privacy gates."""

from __future__ import annotations

import sys
from pathlib import Path

VERIFICATION_DIR = Path(__file__).resolve().parent
if str(VERIFICATION_DIR) not in sys.path:
    sys.path.insert(0, str(VERIFICATION_DIR))

import verify_kotlin_project as vkp  # noqa: E402


def test_kotlin_project_full_verification_passes() -> None:
    report = vkp.run_all_checks()
    assert report["ok"], f"Project verification errors: {report['errors']}"
    assert report["junit_test_methods"] >= 38
    assert report["privacy_hits"] == 0
    assert report["kt_kts_checked"] >= 30


def test_delimiter_lexer_handles_strings_comments_and_templates() -> None:
    valid_kt = '''
    package com.example
    // comment with unmatched { ( [
    /* nested /* comment with } ] ) */ still inside */
    class Demo {
        val raw = """raw string with { and ${1 + 2} and "quotes" """
        val str = "escaped \\" { [ and ${listOf(1, 2).size}"
        val ch = '}'
    }
    '''
    assert vkp.check_kotlin_delimiters(valid_kt, "Valid.kt") == []


def test_delimiter_lexer_detects_real_mismatches() -> None:
    bad_kt = """
    class Broken {
        fun test() {
            val x = listOf(1, 2]
        }
    }
    """
    errs = vkp.check_kotlin_delimiters(bad_kt, "Broken.kt")
    assert len(errs) >= 1
    assert any("mismatched ']'" in e for e in errs)
