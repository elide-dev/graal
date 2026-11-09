#!/usr/bin/env python3
"""Bounded fork validation. Evidence is retained; readiness is reviewed separately."""
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import time

source = Path.cwd()
subject = json.loads((source / 'ci/value-subject.json').read_text())
run_id = os.environ['GITHUB_RUN_ID']
results = Path(os.environ['RUNNER_TEMP']) / 'candidate-results'
results.mkdir(exist_ok=False)
baseline = Path('/build/worktrees/graal') / ('value-ci-' + run_id)
cache = Path('/build/caches') / ('graal-value-ci-' + run_id)
mx = str(Path(os.environ['GITHUB_WORKSPACE']) / 'mx/mx')
receipts = []


def git(*args, cwd=source):
    return subprocess.check_output(['git', *args], cwd=cwd, text=True).strip()


def run(label, tree, args, suite='substratevm', expected=None, timeout=3600):
    env = os.environ.copy()
    variant = 'baseline' if tree == baseline else 'candidate'
    env['MX_ALT_OUTPUT_ROOT'] = str(cache / variant)
    command = [mx, '--max-cpus=8', '--native-images=false', *args]
    start = time.time()
    with (results / (label + '.log')).open('wb') as output:
        completed = subprocess.run(command, cwd=tree / suite, env=env,
                                   stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
    receipts.append(dict(label=label, command=command, cwd=suite,
                         revision=subject['upstreamBase'] if tree == baseline else subject['cleanHead'],
                         exit=completed.returncode, seconds=time.time() - start))
    (results / 'commands.json').write_text(json.dumps(receipts, indent=2) + '\n')
    log = (results / (label + '.log')).read_text(errors='replace')
    if expected:
        if completed.returncode == 0 or expected not in log:
            raise RuntimeError('Expected baseline defect absent: ' + label)
    elif completed.returncode:
        raise RuntimeError('Failed: ' + label)
    if not expected and label.endswith('reproducer') and 'OK (' not in log:
        raise RuntimeError('No completed native JUnit tests: ' + label)


try:
    clean_files = git('diff', '--name-only', subject['upstreamBase'], subject['cleanHead']).splitlines()
    for path in clean_files:
        if git('rev-parse', 'HEAD:' + path) != git('rev-parse', subject['cleanHead'] + ':' + path):
            raise RuntimeError('Candidate file mismatch: ' + path)
    scaffold = git('diff', '--name-only', subject['cleanHead'], 'HEAD').splitlines()
    if any(path not in ('ci/validate-value.py', 'ci/value-subject.json', '.github/workflows/upstream-value.yml') for path in scaffold):
        raise RuntimeError('Unexpected scaffold difference: ' + repr(scaffold))
    baseline.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(['git', 'worktree', 'add', '--detach', str(baseline), subject['upstreamBase']], check=True)
    hashes = {}
    for path in subject['tests']:
        dst = baseline / path
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / path, dst)
        hashes[path] = hashlib.sha256(dst.read_bytes()).hexdigest()
    identities = dict(subject=subject, forkHead=git('rev-parse', 'HEAD'), scaffold=scaffold,
                      testHashes=hashes, mx=git('rev-parse', 'HEAD', cwd=Path(mx).parent),
                      platform=platform.platform(), architecture=platform.machine(),
                      cpu=subprocess.check_output(['lscpu'], text=True),
                      jdk=subprocess.run([os.environ['JAVA_HOME'] + '/bin/java', '-version'],
                                         capture_output=True, text=True, check=True).stderr)
    (results / 'identities.json').write_text(json.dumps(identities, indent=2) + '\n')
    for variant, tree in [('baseline', baseline), ('candidate', source)]:
        run(variant + '-build', tree, ['build', '--build-logs=silent'])
        if subject['kind'] == 'nfi':
            link = cache / variant / 'substratevm/svmbuild/linux-amd64/nfi-test-native'
            link.parent.mkdir(parents=True, exist_ok=True)
            link.symlink_to(cache / variant / 'truffle/linux-amd64/truffle-test-native')
        run(variant + '-reproducer', tree, subject['regression'],
            expected=subject.get('diagnostic') if variant == 'baseline' else None)
        if subject['kind'] == 'nfi':
            dst = results / variant
            dst.mkdir()
            shutil.copy2(cache / variant / 'substratevm/svmbuild/linux-amd64/junit/svmjunit', dst / 'svmjunit')
    if subject['kind'] == 'nfi':
        shutil.copy2(cache / 'baseline/truffle/linux-amd64/truffle-test-native/libnativetest.so', results / 'libnativetest.so')
        for round_index in range(6):
            order = ['baseline', 'candidate'] if round_index % 2 == 0 else ['candidate', 'baseline']
            for variant in order:
                command = ['./svmjunit', '-Dnative.test.path=..', '-Dnfi.test.reportCallOverhead=true', '-Dpolyglot.engine.BackgroundCompilation=false',
                           '--run-explicit', 'com.oracle.truffle.nfi.test.NFICallOverheadTest', '--very-verbose']
                with (results / f'{variant}-timing-{round_index}.log').open('wb') as output:
                    subprocess.run(command, cwd=results / variant, stdout=output, stderr=subprocess.STDOUT, timeout=180, check=True)
        # Keep measurements and identities, not large executables in the workflow artifact.
        for variant in ['baseline', 'candidate']:
            binary = results / variant / 'svmjunit'
            (results / (variant + '-binary.sha256')).write_text(hashlib.sha256(binary.read_bytes()).hexdigest() + '\n')
            shutil.rmtree(results / variant)
    for suite in subject.get('styleSuites', ['substratevm']):
        run(suite + '-checkstyle', source, ['checkstyle', '--primary'], suite=suite, timeout=1200)
    if git('diff', '--name-only'):
        raise RuntimeError('Tracked source changed during validation')
    (results / 'result.txt').write_text('Source equivalence, baseline/candidate comparison, regression and style checks completed.\n')
finally:
    # Preserve artifacts and never remove another job's workspace or cache.
    if baseline.exists():
        subprocess.run(['git', 'worktree', 'remove', '--force', str(baseline)], check=False)
    shutil.rmtree(cache, ignore_errors=True)
