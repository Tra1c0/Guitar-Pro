#!/usr/bin/env python3
"""Checks dependency direction, traceability, generated parity and explicit pending contracts."""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
features = json.loads((ROOT / 'spec/features.json').read_text())['features']
contracts = (ROOT / 'docs/CONTRACTS.md').read_text()
pages = (ROOT / 'feature/workspace/src/main/kotlin/dev/riffworkbench/feature/workspace/WorkspaceScreen.kt').read_text()
routes = set(re.findall(r'\("([a-z]+)", R.string', pages)) | {'settings'}
assert len(features) >= 178, 'Briefing coverage unexpectedly shrank'
assert len({f['id'] for f in features}) == len(features), 'Duplicate feature ID'
for f in features:
    assert f['route'] in routes, f'Unknown route: {f["id"]}'
    assert set(f['dimensions']) == {'READ', 'DISPLAY', 'EDIT', 'PLAYBACK', 'EXPORT'}, f['id']
    assert f'## {f["contractId"]}\n' in contracts, f'Missing contract: {f["id"]}'

graph = {}
for build in ROOT.glob('**/build.gradle.kts'):
    if 'build' in build.relative_to(ROOT).parts[:-1]:
        continue
    module = ':'.join(build.parent.relative_to(ROOT).parts)
    graph[module] = re.findall(r'project\(":([\w:]+)"\)', build.read_text())
    if module.startswith('core:'):
        assert all(dep.startswith('core:') for dep in graph[module]), f'Core dependency leak: {module}'
        for source in build.parent.glob('src/main/**/*.kt'):
            assert not re.search(r'^import (android\.|androidx\.|java\.|javax\.|alphaTab\.)', source.read_text(), re.M), f'Non-portable core: {source}'

def visit(module, path):
    assert module not in path, f'Dependency cycle: {path + [module]}'
    for dependency in graph[module]:
        assert dependency in graph, f'Unknown module {dependency}'
        visit(dependency, path + [module])
for module in graph:
    visit(module, [])
for source in ROOT.glob('**/src/main/**/*.kt'):
    for contract in re.findall(r'(?:unavailable\(|contractId\s*=\s*)"(C-[A-Z-]+)"', source.read_text()):
        assert f'## {contract}\n' in contracts, f'Undocumented pending implementation: {source} {contract}'
    assert 'TODO(' not in source.read_text() and 'NotImplementedError' not in source.read_text(), f'Uncontracted stub: {source}'
subprocess.run([sys.executable, str(ROOT / 'tools/generate_registry.py'), '--check'], check=True)
print(f'Architecture OK: {len(graph) - 1} modules; {len(features)} features; dependency and contract checks passed.')
