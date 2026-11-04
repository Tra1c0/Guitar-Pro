#!/usr/bin/env python3
"""Collect actual JUnit results; fails on missing or failing suites."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--output', default='build/verification.json')
parser.add_argument('--gradle-log')
args = parser.parse_args()
if args.gradle_log:
    states = re.findall(r'BUILD (SUCCESSFUL|FAILED)', Path(args.gradle_log).read_text())
    assert states and states[-1] == 'SUCCESSFUL', 'Gradle did not complete successfully'
suites = []
unique = set()
for path in sorted(ROOT.glob('**/build/test-results/test*/TEST-*.xml')):
    root = ET.parse(path).getroot()
    assert int(root.attrib.get('failures', 0)) == 0 and int(root.attrib.get('errors', 0)) == 0, f'Failing suite: {path}'
    suites.append({'name': root.attrib['name'], 'tests': int(root.attrib['tests']), 'variant': path.parent.name, 'report': str(path.relative_to(ROOT))})
    for case in root.findall('testcase'):
        if case.find('skipped') is None:
            unique.add((case.attrib['classname'], case.attrib['name']))
assert suites and len(unique) >= 50, 'Incomplete verification run'
sources = sorted(p for p in ROOT.rglob('*.kt') if 'build' not in p.relative_to(ROOT).parts)
fingerprint = hashlib.sha256(b''.join(str(p.relative_to(ROOT)).encode() + b'\0' + p.read_bytes() for p in sources)).hexdigest()
result = {'generatedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'sourceSha256': fingerprint, 'uniqueTestsPassed': len(unique), 'testExecutions': sum(s['tests'] for s in suites), 'suites': suites,
    'deviceTests': 'not run', 'gpDesktopRoundTrip': 'not run', 'cubaseHandoff': 'not run', 'audioPerformance': 'not measured'}
target = ROOT / args.output
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps(result, indent=2) + '\n')
print(f'Verified {len(unique)} distinct tests, {result["testExecutions"]} executions. {target.relative_to(ROOT)}')
