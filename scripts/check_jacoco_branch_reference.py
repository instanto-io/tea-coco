#!/usr/bin/env python3
"""Run genuine JaCoCo reports for complementary inputs; verify its branch-counting contract."""
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
fixture = root / 'research/jacoco-branch-reference'
results = {}
for mode in ['A', 'B', 'UNION']:
    log = root / 'target/branch-reference' / (mode + '.log')
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('w') as output:
        subprocess.run(['mvn', '-B', '-ntp', '-s', str(root / 'central-settings.xml'),
                        '-f', str(fixture / 'pom.xml'), 'clean', 'verify', '-Dreference.mode=' + mode],
                       stdout=output, stderr=subprocess.STDOUT, check=True)
    report = fixture / 'target/site/jacoco/jacoco.xml'
    (log.parent / (mode + '.xml')).write_bytes(report.read_bytes())
    (log.parent / (mode + '.exec')).write_bytes((fixture / 'target/jacoco.exec').read_bytes())
    methods = ET.parse(report).getroot().find("package/class[@name='reference/Decisions']").findall('method')
    results[mode] = {}
    for method in methods:
        if method.get('name') == '<init>':
            continue
        counter = method.find("counter[@type='BRANCH']")
        missed, covered = (0, 0) if counter is None else (int(counter.get('missed')), int(counter.get('covered')))
        results[mode][method.get('name')] = {'covered': covered, 'total': missed + covered}

# JaCoCo's raw probe identities can merge complementary executions exactly.
with (root / 'target/branch-reference/merge.log').open('w') as output:
    subprocess.run(['mvn', '-B', '-ntp', '-s', str(root / 'central-settings.xml'),
                    '-f', str(fixture / 'pom.xml'), '-Pmerge-reference',
                    '-Dreference.mergeDirectory=' + str(root / 'target/branch-reference'),
                    'org.jacoco:jacoco-maven-plugin:0.8.15:merge',
                    'org.jacoco:jacoco-maven-plugin:0.8.15:report',
                    '-Djacoco.dataFile=' + str(fixture / 'target/merged.exec')],
                   stdout=output, stderr=subprocess.STDOUT, check=True)
merged = ET.parse(fixture / 'target/site/jacoco/jacoco.xml').getroot()
union = ET.parse(root / 'target/branch-reference/UNION.xml').getroot()
for document in [merged, union]:
    for session in document.findall('sessioninfo'):
        document.remove(session)
assert ET.tostring(merged) == ET.tostring(union), 'Raw-probe union differs from executing both input sets'

expected = {
    'simple': (1, 1, 2, 2), 'and': (2, 3, 4, 4), 'or': (2, 3, 4, 4),
    'ternary': (1, 1, 2, 2), 'loop': (2, 1, 2, 2), 'sharedSwitch': (1, 2, 3, 3),
    'singleDestinationSwitch': (0, 0, 0, 0), 'fallThrough': (1, 2, 3, 3),
    'stringSwitch': (1, 2, 3, 3), 'exceptions': (0, 0, 0, 0),
    'eliminatedByJavac': (0, 0, 0, 0), 'unreachable': (0, 0, 0, 2),
}
for name, (a, b, union, total) in expected.items():
    for mode, covered in [('A', a), ('B', b), ('UNION', union)]:
        assert results[mode][name] == {'covered': covered, 'total': total}, (mode, name, results[mode][name])
(root / 'target/branch-reference/results.json').write_text(json.dumps(results, indent=2) + '\n')
print('Method | A covered | B covered | A+B covered | Total branches')
for name in expected:
    print(name, *(results[mode][name]['covered'] for mode in results), results['UNION'][name]['total'], sep=' | ')
print('All JaCoCo branch reference assertions passed, including exact raw-probe union.')
