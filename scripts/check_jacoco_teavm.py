#!/usr/bin/env python3
"""Prove genuine JaCoCo agent/browser probe parity, exact union and compiler evidence."""
import csv
import json
import xml.etree.ElementTree as ET
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
subprocess.run(["python3", str(root / "scripts/check_jacoco_branch_reference.py")], check=True, cwd=root)
subprocess.run([
    "mvn", "-B", "-ntp", "-s", str(root / "central-settings.xml"),
    "-f", str(root / "research/jacoco-teavm-prototype/pom.xml"), "clean", "verify",
    "-Dprototype.agentData=" + str(root / "target/branch-reference"),
], check=True, cwd=root)
print("JaCoCo agent, JVM custom runtime and stock TeaVM browser probes agree exactly.")
print("See research/jacoco-teavm-prototype/target/proof for raw .exec, XML and compiler evidence.")

proof = root / "research/jacoco-teavm-prototype/target/proof"
for level in ["SIMPLE", "ADVANCED", "FULL"]:
    source = ET.parse(proof / (level + "-browser-UNION.xml")).getroot().find("package/sourcefile")
    with (proof / (level + "-uninstrumented") / "reachability.tsv").open() as stream:
        reachability = list(csv.DictReader(stream, delimiter="\t"))
    with (proof / (level + "-uninstrumented") / "emission.tsv").open() as stream:
        emission = list(csv.DictReader(stream, delimiter="\t"))
    locations = {int(row["line"]) for row in emission}
    rows = []
    for line in source.findall("line"):
        number = int(line.get("nr"))
        owners = [row for row in reachability if int(row["line"]) == number]
        omitted = bool(owners) and all(row["status"] == "unreachable-by-dependency-analysis" for row in owners)
        rows.append({
            "line": number,
            "executed": int(line.get("ci")) > 0,
            "coveredBranches": int(line.get("cb")),
            "totalBranches": int(line.get("cb")) + int(line.get("mb")),
            "compilerEvidence": "unreachable-method" if omitted else
                "emitted-source-location" if number in locations else "no-emitted-location-not-proof-of-removal",
        })
    retained = [row for row in rows if row["compilerEvidence"] != "unreachable-method"]
    def counts(values):
        return {"coveredLines": sum(row["executed"] for row in values), "totalLines": len(values),
                "coveredBranches": sum(row["coveredBranches"] for row in values),
                "totalBranches": sum(row["totalBranches"] for row in values)}
    summary = {"optimization": level, "originalBytecode": counts(rows),
               "reachableBytecodeDiagnostic": counts(retained), "lines": rows}
    assert summary["originalBytecode"] == {"coveredLines": 31, "totalLines": 32, "coveredBranches": 23, "totalBranches": 25}
    assert summary["reachableBytecodeDiagnostic"] == {"coveredLines": 31, "totalLines": 31, "coveredBranches": 23, "totalBranches": 23}
    (proof / (level + "-coverage-and-emission.json")).write_text(json.dumps(summary, indent=2) + "\n")
    print(level + ": original 31/32 lines, 23/25 branches; excluding compiler-unreachable methods: 31/31 lines, 23/23 branches.")
