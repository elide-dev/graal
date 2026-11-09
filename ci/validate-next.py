#!/usr/bin/env python3
"""Fork-only, bounded comparison harness; no automatic qualification claims."""
import hashlib
import json
import os
from pathlib import Path
import platform
import resource
import shutil
import subprocess
import tempfile
import time

resource.setrlimit(resource.RLIMIT_CORE, (0, 0))

source = Path.cwd()
subject = json.loads((source / 'ci/next-subject.json').read_text())
results = Path(os.environ['RUNNER_TEMP']) / 'candidate-results'
if results.exists():
    if results.is_symlink():
        raise SystemExit('Unexpected results symlink; retain for investigation')
    retained = Path(tempfile.mkdtemp(prefix='prior-candidate-results-', dir=os.environ['RUNNER_TEMP']))
    results.rename(retained / 'results')
    print('Retained earlier runner artifacts at ' + str(retained), flush=True)
results.mkdir()
scratch = Path(tempfile.mkdtemp(prefix='upstream-next-', dir='/tmp'))
baseline = scratch / 'baseline/source'
baseline.parent.mkdir()
mx = str(Path(os.environ['GITHUB_WORKSPACE']) / 'mx/mx')
suite = 'graal-js' if subject['repository'] == 'graaljs' else 'substratevm'
receipts = []


def git(*args, cwd=source):
    return subprocess.check_output(['git', *args], cwd=cwd, text=True).strip()


def run(label, tree, args, expected=None, timeout=1800):
    env = os.environ.copy()
    env['MX_ALT_OUTPUT_ROOT'] = str(scratch / ('build-' + ('baseline' if tree == baseline else 'candidate')))
    command = [mx, '--max-cpus=8']
    if suite == 'substratevm':
        command += ['--native-images=false']
    command += args
    start = time.time()
    with (results / (label + '.log')).open('wb') as output:
        completed = subprocess.run(command, cwd=tree / suite, env=env, stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
    receipt = dict(label=label, command=command, cwd=suite, revision=subject['upstreamBase'] if tree == baseline else subject['cleanHead'], exit=completed.returncode, seconds=time.time() - start)
    receipts.append(receipt)
    (results / 'commands.json').write_text(json.dumps(receipts, indent=2) + '\n')
    log = (results / (label + '.log')).read_text(errors='replace')
    if expected:
        if completed.returncode == 0 or expected not in log:
            raise RuntimeError('Required baseline defect was not observed: ' + label)
    elif completed.returncode:
        raise RuntimeError('Failed: ' + label)


try:
    head = git('rev-parse', 'HEAD')
    clean_files = git('diff', '--name-only', subject['upstreamBase'], subject['cleanHead']).splitlines()
    for path in clean_files:
        if git('rev-parse', 'HEAD:' + path) != git('rev-parse', subject['cleanHead'] + ':' + path):
            raise RuntimeError('Fork file differs from clean candidate: ' + path)
    scaffold = git('diff', '--name-only', subject['cleanHead'], head).splitlines()
    if any(not path.startswith('ci/') and path != '.github/workflows/upstream-next.yml' for path in scaffold):
        raise RuntimeError('Unexpected fork scaffolding: ' + repr(scaffold))
    base = subject['upstreamBase']
    subprocess.run(['git', 'worktree', 'add', '--detach', str(baseline), base], check=True)
    if suite == 'graal-js':
        imported = Path(os.environ['GITHUB_WORKSPACE']) / 'graal'
        if git('rev-parse', 'HEAD', cwd=imported) != subject['graalImport']:
            raise RuntimeError('Wrong Graal import')
        (baseline.parent / 'graal').symlink_to(imported)
    hashes = {}
    for path in subject['tests']:
        destination = baseline / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / path, destination)
        hashes[path] = hashlib.sha256(destination.read_bytes()).hexdigest()
        if destination.read_bytes() != (source / path).read_bytes():
            raise RuntimeError('Test payload changed')
    environment = dict(platform=platform.platform(), machine=platform.machine(), runner=os.environ.get('RUNNER_NAME'), forkHead=head, subject=subject, scaffold=scaffold, hashes=hashes, mx=git('rev-parse', 'HEAD', cwd=Path(mx).parent))
    environment['jdk'] = subprocess.run([os.environ['JAVA_HOME'] + '/bin/java', '-version'], capture_output=True, text=True, check=True).stderr
    environment['cpu'] = subprocess.check_output(['lscpu'], text=True)
    (results / 'environment.json').write_text(json.dumps(environment, indent=2) + '\n')
    shutil.copyfile(source / 'ci/next-subject.json', results / 'subject.json')
    dependencies = ['--dependencies', 'TRUFFLE_JS_TESTS,GRAALJS_LAUNCHER,truffle:TRUFFLE_RUNTIME'] if suite == 'graal-js' else []
    run('baseline-build', baseline, ['build', '--build-logs=silent', *dependencies])
    run('baseline-reproducer', baseline, subject['regression'], expected=subject['diagnostic'])
    if subject.get('generatedSample'):
        sample = scratch / 'build-baseline' / subject['generatedSample']
        shutil.copyfile(sample, results / 'baseline-sample.java')
    run('candidate-build', source, ['build', '--build-logs=silent', *dependencies])
    run('candidate-reproducer', source, subject['regression'])
    if subject.get('generatedSample'):
        sample = scratch / 'build-candidate' / subject['generatedSample']
        shutil.copyfile(sample, results / 'candidate-sample.java')
        if (results / 'baseline-sample.java').read_bytes() != sample.read_bytes():
            raise RuntimeError('Generated sample bytes differ')
        (results / 'generated-sample-sha256.txt').write_text(hashlib.sha256(sample.read_bytes()).hexdigest() + '\n')
    if subject.get('suite'):
        run('regressions', source, subject['suite'])
    run('checkstyle', source, ['checkstyle', '--primary'], timeout=600)
    if git('diff', '--name-only'):
        raise RuntimeError('Candidate tracked files changed during validation')
    (results / 'result.txt').write_text('Expected baseline failure; byte-identical regression passes on exact candidate; style passed.\n')
finally:
    # Logs/identities remain in artifact output; delete only owned disposable builds.
    if baseline.exists():
        for path in subject['tests']:
            (baseline / path).unlink(missing_ok=True)
        subprocess.run(['git', 'worktree', 'remove', str(baseline)], check=True)
    shutil.rmtree(scratch, ignore_errors=True)
