#!/usr/bin/env python3
"""Check the server's imported counters against the generated JaCoCo XML, without changing gates."""
import argparse
import json
import os
from pathlib import Path
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--module", type=Path, default=Path("jacoco-example"))
parser.add_argument("--project", default="teacoco-jacoco-pilot")
args = parser.parse_args()
host = os.environ["SONAR_HOST_URL"].rstrip("/")
token = os.environ.get("SONAR_READ_TOKEN") or os.environ["SONAR_TOKEN"]
def get(path, **params):
    request = urllib.request.Request(host + path + "?" + urllib.parse.urlencode(params),
                                     headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)

properties = dict(line.split("=", 1) for line in (args.module / "target/sonar/report-task.txt").read_text().splitlines() if "=" in line)
for attempt in range(100):
    task = get("/api/ce/task", id=properties["ceTaskId"])["task"]
    if task["status"] in ["SUCCESS", "FAILED", "CANCELED"]:
        break
    time.sleep(3)
assert task["status"] == "SUCCESS", task["status"]
metrics = ["lines_to_cover", "uncovered_lines", "conditions_to_cover", "uncovered_conditions", "coverage", "line_coverage", "branch_coverage"]
response = get("/api/measures/component", component=args.project, metricKeys=",".join(metrics))
actual = {m["metric"]: float(m["value"]) for m in response["component"]["measures"]}
report = ET.parse(args.module / "target/teavm-coverage.xml").getroot()
counters = {c.get("type"): (int(c.get("covered")), int(c.get("missed"))) for c in report.findall("counter")}
lines, missed_lines = counters["LINE"]
branches, missed_branches = counters["BRANCH"]
expected = {"lines_to_cover": lines + missed_lines, "uncovered_lines": missed_lines,
            "conditions_to_cover": branches + missed_branches, "uncovered_conditions": missed_branches}
for name, value in expected.items():
    assert actual[name] == value, (name, actual[name], value)
percentage = 100 * (lines + branches) / (lines + missed_lines + branches + missed_branches)
assert abs(actual["coverage"] - percentage) <= 0.11, (actual["coverage"], percentage)
gate = get("/api/qualitygates/project_status", analysisId=task["analysisId"])["projectStatus"]
summary = {"project": args.project, "analysisId": task["analysisId"], "expected": expected,
           "actual": actual, "computedCoverage": percentage, "qualityGate": gate,
           "note": "First analysis verifies overall coverage; new-code behavior requires a later baseline comparison."}
target = args.module / "target/sonar-import-verification.json"
target.write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))
assert gate["status"] != "ERROR", "Sonar quality gate failed; thresholds were not changed"
