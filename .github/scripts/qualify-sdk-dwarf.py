"""Verify final SDK probe bytes using the final packaged LLVM verifier."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
from producer_artifact import download_artifact

platform = sys.argv[1]
root = Path('qualified-sdk')
root.mkdir()
catalog = json.loads(Path('.github/qualification/debug-quality-producers.json').read_text())
sdk = catalog[platform]['sdk']
overrides = os.environ.get('QUALIFICATION_SDK_RUNS', '')
if overrides:
    import re
    runs = json.loads(overrides)
    source = os.environ['QUALIFICATION_SDK_SOURCE']
    assert set(runs) == {'linux-amd64', 'linux-arm64'}
    assert re.fullmatch('[0-9a-f]{40}', source)
    assert all(type(run) is int and run > 0 for run in runs.values())
    sdk = {'run': runs[platform], 'commit': source, 'scope': 'Exact successful normal full SDK build'}
    metadata = json.loads(subprocess.check_output(['gh', 'run', 'view', str(sdk['run']), '--repo', 'elide-dev/graal', '--json', 'headSha,conclusion'], text=True))
    assert metadata['headSha'] == source and metadata['conclusion'] == 'success'
name = 'debug-quality-' + {'linux-amd64': 'linux-amd64', 'linux-arm64': 'linux-aarch64'}[platform] + '-' + sdk['commit']
metadata = json.loads(subprocess.check_output(['gh', 'api', f"repos/elide-dev/graal/actions/runs/{sdk['run']}/artifacts?per_page=100"], text=True))
artifact = max((item for item in metadata['artifacts'] if item['name'] == name and not item['expired']), key=lambda item: item['id'])
probe = root / 'probe'
download_artifact({'run': sdk['run'], 'commit': sdk['commit'], 'artifact': name, 'artifactId': artifact['id']}, 'elide-dev/graal', probe)
for line in (probe / 'SHA256SUMS').read_text().splitlines():
    digest, name = line.split(maxsplit=1)
    path = probe / Path(name.strip()).name
    assert hashlib.file_digest(path.open('rb'), 'sha256').hexdigest() == digest
assert 'Optimized source, inline chain, live values and code-less declaration verified' in (probe / 'gdb.txt').read_text()
native = json.loads(Path('.github/qualification/debug-quality-native.json').read_text())
folder = root / 'native-package'
download_artifact(native, 'elide-dev/toolchain', folder)
archive = folder / native['file']
assert hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest() == native['sha256']
with tarfile.open(archive) as stream:
    stream.extractall(root / 'native', filter='data')
verifier, = (root / 'native').rglob('llvm-dwarfdump')
subprocess.run([sys.executable, '.github/scripts/verify-elf-product-ci.py', str(probe / 'probe'), str(probe / 'probe.debug'), str(verifier), str(root / 'verification')], check=True)
with (root / 'type-files.json').open('w') as output:
    subprocess.run([sys.executable, '.github/scripts/verify-probe-type-files.py', str(probe / 'probe.debug'), 'DebugQualityProbe.java'], stdout=output, check=True)
(root / 'provenance.json').write_text(json.dumps({'sdk': sdk, 'native': native, 'probeArtifactId': artifact['id'], 'scope': 'Actual optimized output of the final normal SDK build; source, inline, live value and declaration GDB proof plus exact packaged verifier and all declaration-file references.'}, indent=2) + '\n')
