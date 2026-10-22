#!/usr/bin/env python3
"""Summarize JUnit and JaCoCo results as Markdown for the GitHub Actions run summary."""

import os
from pathlib import Path
import xml.etree.ElementTree as ElementTree


BUILD_REPORTS = Path("build/reports")
TEST_RESULTS = Path("build/test-results/test")
OUTPUT = BUILD_REPORTS / "quality-summary.md"


def percentage(covered: int, missed: int) -> str:
    total = covered + missed
    return "n/a" if total == 0 else f"{covered / total:.1%}"


def test_summary() -> str:
    files = list(TEST_RESULTS.glob("TEST-*.xml"))
    if not files:
        return "⚪ Not available"

    tests = failures = errors = skipped = 0
    for file in files:
        suite = ElementTree.parse(file).getroot()
        tests += int(suite.get("tests", "0"))
        failures += int(suite.get("failures", "0"))
        errors += int(suite.get("errors", "0"))
        skipped += int(suite.get("skipped", "0"))

    passed = tests - failures - errors - skipped
    status = "✅" if failures + errors == 0 else "❌"
    return f"{status} {passed} passed, {failures + errors} failed, {skipped} skipped"


def jacoco_rows() -> list[str]:
    report = BUILD_REPORTS / "jacoco/test/jacocoTestReport.xml"
    if not report.exists():
        return ["| JaCoCo | n/a | n/a | Not available |"]

    root = ElementTree.parse(report).getroot()
    counters = {counter.get("type"): counter for counter in root.findall("counter")}
    rows = []
    for counter_type, label in (("LINE", "Lines"), ("BRANCH", "Branches"), ("METHOD", "Methods")):
        counter = counters.get(counter_type)
        if counter is None:
            continue
        covered = int(counter.get("covered", "0"))
        missed = int(counter.get("missed", "0"))
        rows.append(f"| {label} | {covered} | {missed} | {percentage(covered, missed)} |")
    return rows


def run_url() -> str | None:
    server = os.getenv("GITHUB_SERVER_URL")
    repository = os.getenv("GITHUB_REPOSITORY")
    run_id = os.getenv("GITHUB_RUN_ID")
    if not all((server, repository, run_id)):
        return None
    return f"{server}/{repository}/actions/runs/{run_id}"


def main() -> None:
    lines = [
        "<!-- ci-quality-summary -->",
        "## CI quality summary",
        "",
        f"**Tests:** {test_summary()}",
        "",
        "### JaCoCo coverage",
        "",
        "| Metric | Covered | Missed | Coverage |",
        "|---|---:|---:|---:|",
        *jacoco_rows(),
    ]

    workflow_url = run_url()
    if workflow_url:
        lines.extend(("", f"[Open CI run and artifacts]({workflow_url})"))

    lines.extend(("", "_Updated automatically by GitHub Actions._", ""))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    main()
