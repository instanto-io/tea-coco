#!/usr/bin/env python3
"""Exercise the published plugin marker with upstream TeaVM's Gradle library/application plugins."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--published', action='store_true', help='Resolve from Instanto, never mavenLocal')
args = parser.parse_args()
project = Path(__file__).resolve().parents[1]
registry = '''maven {
    url = uri('https://packages.instanto.io/api/packages/instanto-io/maven')
    credentials {
        username = System.getenv('FORGEJO_PACKAGE_USER')
        password = System.getenv('FORGEJO_PACKAGE_TOKEN')
    }
    content { includeGroupByRegex('io\\\\.instanto.*') }
}''' if args.published else 'mavenLocal()'

with tempfile.TemporaryDirectory(prefix='tea-coco-gradle-consumer-') as temporary:
    consumer = Path(temporary)
    shutil.copytree(project / 'example/src', consumer / 'src')
    (consumer / 'settings.gradle').write_text('pluginManagement { repositories {\n' + registry + '''
        gradlePluginPortal()
        mavenCentral()
    } }
rootProject.name = 'independent-gradle-consumer'
''')
    (consumer / 'build.gradle').write_text('''plugins {
    id 'java'
    id 'org.teavm.library' version '0.16.0'
    id 'io.instanto.tea-coco' version '0.1.0-SNAPSHOT'
}
repositories {
''' + registry + '''
    mavenCentral()
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies {
    testImplementation 'junit:junit:4.13.2'
    testRuntimeOnly 'org.teavm:teavm-jso-impl:0.16.0'
}
test {
    useJUnit()
    include '**/RoutesTest.class'
    systemProperty 'teavm.junit.js', 'true'
    systemProperty 'teavm.junit.js.runner', 'browser-chrome'
    systemProperty 'teavm.junit.minified', 'true'
    systemProperty 'teavm.junit.optimized', 'true'
}
''')
    command = [str(project / 'gradlew'), '-p', str(consumer), '--no-daemon', '--stacktrace']
    if args.published:
        command += ['--gradle-user-home', str(consumer / 'fresh-gradle-home')]
    def run(*arguments, success=True):
        result = subprocess.run(command + list(arguments), cwd=consumer)
        assert (result.returncode == 0) == success, (arguments, result.returncode)
    def check_report():
        report = ET.parse(consumer / 'build/reports/tea-coco/coverage.xml').getroot()
        hits = {int(e.attrib['lineNumber']): e.attrib['covered'] == 'true'
                for e in report.iter('lineToCover')}
        for n, line in enumerate((consumer / 'src/main/java/example/Routes.java').read_text().splitlines(), 1):
            if '// covered' in line:
                assert hits.get(n) is True, n
            if '// uncovered' in line:
                assert hits.get(n) is False, n
            if '// not emitted' in line:
                assert n not in hits, n
        omissions = json.loads((consumer / 'build/reports/tea-coco/not-emitted.json').read_text())
        assert omissions
    run('clean', 'teaCocoReport')
    check_report()
    initial_ids = {p.name for p in (consumer / 'build/tea-coco/data').glob('*.json')}
    # No clean: test execution must discard stale compilation IDs rather than use cached evidence.
    run('teaCocoReport')
    check_report()
    assert not initial_ids.intersection(p.name for p in (consumer / 'build/tea-coco/data').glob('*.json'))
    # The same integration works alongside TeaVM's application plugin.
    build = consumer / 'build.gradle'
    build.write_text(build.read_text().replace("'org.teavm.library'", "'org.teavm'"))
    run('clean', 'teaCocoReport')
    check_report()
    # Real failing browser tests still produce a report and retain the failing Gradle exit code.
    tests = consumer / 'src/test/java/example/RoutesTest.java'
    tests.write_text(tests.read_text().replace('assertEquals(13,', 'assertEquals(99,'))
    run('clean', 'test', success=False)
    check_report()
    tests.write_text(tests.read_text().replace('assertEquals(99,', 'assertEquals(13,'))
    run('clean', 'test', '-PteaCoco.enabled=false')
    assert not (consumer / 'build/tea-coco/data').exists()
    assert not (consumer / 'build/reports/tea-coco/coverage.xml').exists()
    for script in (consumer / 'build').rglob('*.js'):
        assert '__teavmCoverageV1' not in script.read_text(), script
    print('Gradle consumer passed: upstream library/app plugins, fresh evidence, failure reporting and disabled mode.')
