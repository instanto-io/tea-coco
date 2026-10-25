#!/usr/bin/env python3
"""Prove an unrelated Maven application can consume the installed extension."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--local-repository')
parser.add_argument('--settings', type=Path, help='Maven settings for testing a published snapshot')
args = parser.parse_args()
project = Path(__file__).resolve().parents[1]
namespace = 'http://maven.apache.org/POM/4.0.0'
ET.register_namespace('', namespace)
def tag(name):
    return '{' + namespace + '}' + name

with tempfile.TemporaryDirectory(prefix='teavm-coverage-consumer-') as temporary:
    consumer = Path(temporary)
    shutil.copytree(project / 'example/src', consumer / 'src')
    pom = ET.parse(project / 'example/pom.xml')
    root = pom.getroot()
    root.remove(root.find(tag('parent')))
    ET.SubElement(root, tag('groupId')).text = 'example.independent'
    ET.SubElement(root, tag('version')).text = '1.0-SNAPSHOT'
    properties = root.find(tag('properties'))
    for name, value in {'maven.compiler.release': '17', 'project.build.sourceEncoding': 'UTF-8',
                        'teavm.version': '0.16.0', 'coverage.version': '0.1.0-SNAPSHOT'}.items():
        ET.SubElement(properties, tag(name)).text = value
    for version in root.iter(tag('version')):
        if version.text == '${project.version}':
            version.text = '${coverage.version}'
    plugins = root.find(tag('build')).find(tag('plugins'))
    compiler = ET.SubElement(plugins, tag('plugin'))
    ET.SubElement(compiler, tag('groupId')).text = 'org.apache.maven.plugins'
    ET.SubElement(compiler, tag('artifactId')).text = 'maven-compiler-plugin'
    ET.SubElement(compiler, tag('version')).text = '3.14.1'
    for plugin in plugins:
        artifact = plugin.find(tag('artifactId')).text
        if artifact in ['maven-surefire-plugin', 'maven-failsafe-plugin']:
            ET.SubElement(plugin, tag('version')).text = '3.5.4'
    pom.write(consumer / 'pom.xml', encoding='UTF-8', xml_declaration=True)
    command = ['mvn', '-B', '-ntp', '-s', str((args.settings or project / 'central-settings.xml').resolve())]
    if args.local_repository:
        command += ['-Dmaven.repo.local=' + str(Path(args.local_repository).resolve())]
    subprocess.run(command + ['verify'], cwd=consumer, check=True)
    report = ET.parse(consumer / 'target/teavm-coverage.xml').getroot()
    assert report.tag == 'coverage'
    assert report.find('file') is not None
    subprocess.run(command + ['clean', 'test', '-Dcoverage.enabled=false'], cwd=consumer, check=True)
    assert not (consumer / 'target/teavm-coverage').exists()
    for script in (consumer / 'target/teavm-tests').rglob('*.js'):
        assert '__teavmCoverageV1' not in script.read_text(), script
    print('Independent consumer verified, including disabled mode: no parent, fork, patched JAR or reactor dependency.')
