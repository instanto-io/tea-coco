#!/usr/bin/env python3
"""Prove complementary JVM/browser branches merge and published app classes stay original."""
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parent
target = root / 'target'
paths = {'JVM': target / 'site/jacoco/jacoco.xml',
         'Browser': target / 'browser-coverage.xml',
         'Merged': target / 'teavm-coverage.xml'}
reports = {name: ET.parse(path).getroot() for name, path in paths.items()}
counts = {name: {c.get('type'): (int(c.get('covered')), int(c.get('missed')))
                 for c in report.findall('counter')}
          for name, report in reports.items()}
for metric in ['LINE', 'BRANCH']:
    assert len({sum(c[metric]) for c in counts.values()}) == 1, 'Denominator changed'
    assert counts['Merged'][metric][0] > max(counts['JVM'][metric][0], counts['Browser'][metric][0]), 'No complementary coverage'
for name, covered in [('JVM', 1), ('Browser', 5), ('Merged', 6)]:
    counter = reports[name].find(".//class[@name='io/instanto/mockatcha/examples/TimesheetService']/method[@name='submit']/counter[@type='BRANCH']")
    assert int(counter.get('covered')) == covered, (name, ET.tostring(counter))
    assert int(counter.get('missed')) == 6 - covered
    assert all(c.get('name').startswith('io/instanto/mockatcha/examples/')
               for c in reports[name].findall('.//class')), 'Mock/framework code entered application scope'
class_file = 'io/instanto/mockatcha/examples/TimesheetService.class'
original = (target / 'classes' / class_file).read_bytes()
assert original == (target / 'teavm-coverage/jacoco/originals' / class_file).read_bytes()
assert original != (target / 'teavm-coverage/jacoco/classes' / class_file).read_bytes()
with zipfile.ZipFile(target / 'mockatcha-tea-coco-demo-0.1.0-SNAPSHOT.jar') as jar:
    assert original == jar.read(class_file), 'Published classes were instrumented'
lines = ['# Timesheet coverage comparison', '', '| Runtime | Lines | Branch outcomes |', '| --- | ---: | ---: |']
for name, counters in counts.items():
    line, branch = counters['LINE'], counters['BRANCH']
    lines.append(f'| {name} | {line[0]} / {sum(line)} | {branch[0]} / {sum(branch)} |')
lines += ['', 'The JVM covers the missing-draft path; browser tests cover ordinary weeks and overtime.',
          'The merged report ORs compatible raw probes. Application JAR bytes remain unchanged.', '']
summary = '\n'.join(lines)
(target / 'coverage-comparison.md').write_text(summary)
print(summary)
