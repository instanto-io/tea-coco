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
    shutil.copytree(project / 'jacoco-example/src', consumer / 'src')
    # Maven's Failsafe report assertions are performed below by this Gradle consumer.
    (consumer / 'src/test/java/example/JacocoReportIT.java').unlink()
    (consumer / 'settings.gradle').write_text('pluginManagement { repositories {\n' + registry + '''
        gradlePluginPortal()
        mavenCentral()
    } }
rootProject.name = 'independent-gradle-consumer'
''')
    (consumer / 'build.gradle').write_text('''plugins {
    id 'java'\n    id 'jacoco'
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
teaCoco { mode = 'jacoco' }
test {
    useJUnit()
    include '**/ProbesTest.class'
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
        assert report.tag == 'report'
        for method in ['choose', 'value', 'staticValue']:
            count = report.find(".//method[@name='" + method + "']/counter[@type='BRANCH']")
            assert count is not None and count.get('covered') == '2', method
        missed = report.find(".//method[@name='neverCalled']/counter[@type='BRANCH']")
        assert missed is not None and missed.get('missed') == '2'
        assert (consumer / 'build/reports/tea-coco/coverage.xml.exec').is_file()
        assert (consumer / 'build/reports/tea-coco/compiler-evidence.json').is_file()
    run('clean', 'teaCocoReport')
    check_report()
    initial_id = json.loads((consumer / 'build/tea-coco/data/jacoco/manifest.json').read_text())['run']
    # No clean: test execution must discard stale compilation IDs rather than use cached evidence.
    run('teaCocoReport')
    check_report()
    assert initial_id != json.loads((consumer / 'build/tea-coco/data/jacoco/manifest.json').read_text())['run']
    # The same integration works alongside TeaVM's application plugin.
    build = consumer / 'build.gradle'
    build.write_text(build.read_text().replace("'org.teavm.library'", "'org.teavm'"))
    run('clean', 'teaCocoReport')
    check_report()
    # Real failing browser tests still produce a report and retain the failing Gradle exit code.
    tests = consumer / 'src/test/java/example/ProbesTest.java'
    tests.write_text(tests.read_text().replace('assertTrue(true);', 'assertTrue(false);'))
    run('clean', 'test', success=False)
    check_report()
    tests.write_text(tests.read_text().replace('assertTrue(false);', 'assertTrue(true);'))
    run('clean', 'test', '-PteaCoco.enabled=false')
    assert not (consumer / 'build/tea-coco/data').exists()
    assert not (consumer / 'build/reports/tea-coco/coverage.xml').exists()
    for script in (consumer / 'build').rglob('*.js'):
        assert '__TEA_COCO_JACOCO_V1__' not in script.read_text(), script
    print('Gradle consumer passed: upstream library/app plugins, fresh evidence, failure reporting and disabled mode.')
