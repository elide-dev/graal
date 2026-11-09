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
results = Path(os.environ['RUNNER_TEMP']) / ('candidate-results-' + run_id + '-' + os.environ['GITHUB_RUN_ATTEMPT'])
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


def external(label, command, cwd, env=None, expected=None):
    start = time.time()
    with (results / (label + '.log')).open('wb') as output:
        done = subprocess.run(command, cwd=cwd, env=env, stdout=output,
                              stderr=subprocess.STDOUT, timeout=3600)
    receipts.append(dict(label=label, command=list(map(str, command)), exit=done.returncode,
                         seconds=time.time() - start))
    (results / 'commands.json').write_text(json.dumps(receipts, indent=2) + '\n')
    log = (results / (label + '.log')).read_text(errors='replace')
    if expected:
        if done.returncode != 1 or expected not in log or 'NATIVE_G1_JFR=true' not in log:
            raise RuntimeError('Missing intended native baseline failure: ' + label)
    elif done.returncode:
        raise RuntimeError('Failed: ' + label)
    return log


def prepare_jfr():
    native = Path('/build/worktrees/labs-openjdk') / ('value-ci-' + run_id)
    native.parent.mkdir(parents=True, exist_ok=True)
    git_dir = cache / 'native-git'
    git_dir.mkdir(parents=True)
    subprocess.run(['git', 'init', str(git_dir)], check=True)
    subprocess.run(['git', '-C', str(git_dir), 'fetch', '--depth=1',
                    'https://github.com/graalvm/labs-openjdk.git', subject['nativeRevision']], check=True)
    subprocess.run(['git', '-C', str(git_dir), 'worktree', 'add', '--detach',
                    str(native), 'FETCH_HEAD'], check=True)
    native_env = os.environ.copy()
    native_env.update(MX_ALT_OUTPUT_ROOT=str(cache / 'native'), SVM_GC_TARGETS='build_product_cr_a')
    external('native-g1-build', [mx, '-c', '8', '--multitarget=linux-amd64-glibc',
             'build', '--dependencies=SVM_G1GC', '--build-logs=silent'], native, native_env)
    probe = results / 'probe'
    probe.mkdir()
    for filename, contents in subject['probeSources'].items():
        (probe / filename).write_text(contents)
    include = probe / 'include'
    include.mkdir()
    shutil.copy2(native / 'src/hotspot/svm/exports/g1GCStructs.h', include / 'g1GCStructs.h')
    classes = probe / 'classes'
    classes.mkdir()
    jars = []
    for suite in ('substratevm', 'compiler', 'sdk'):
        jars.extend(sorted((cache / 'baseline' / suite / 'dists').glob('*.jar')))
    # mx reports its pinned library paths, independent of the runner's cache location.
    junit = subprocess.check_output([mx, 'classpath', 'mx:JUNIT', 'mx:HAMCREST'],
                                    cwd=baseline / 'substratevm', text=True).strip()
    cp = os.pathsep.join(map(str, jars)) + os.pathsep + junit
    javac = os.environ['JAVA_HOME'] + '/bin/javac'
    external('provider-javac', [javac, '-cp', cp, '-processorpath', cp, '-processor',
             'jdk.graal.compiler.options.processor.OptionProcessor', '-d', str(classes),
             str(probe / 'G1ReviewOptions.java')], probe)
    external('probe-javac', [javac, '-proc:none', '-cp', cp, '-d', str(classes),
             str(probe / 'JfrNativeReviewMain.java'), str(source / subject['tests'][0])], probe)
    import zipfile
    with zipfile.ZipFile(probe / 'provider.jar', 'w') as provider, zipfile.ZipFile(probe / 'probe.jar', 'w') as app:
        for f in sorted(classes.rglob('*.class')):
            rel = f.relative_to(classes).as_posix()
            (provider if 'G1ReviewOptions' in rel else app).write(f, rel)
        provider.writestr('META-INF/services/jdk.graal.compiler.options.OptionDescriptors',
                          'review.G1ReviewOptions_OptionDescriptors\n')
    archive = cache / 'native/substratevm-gcs/linux-amd64/src/hotspot/linux-amd64/glibc/libg1gc-cr.a'
    hashes = {str(f.relative_to(probe)) if f.is_relative_to(probe) else 'libg1gc-cr.a':
              hashlib.sha256(f.read_bytes()).hexdigest()
              for f in [probe / 'provider.jar', probe / 'probe.jar', include / 'g1GCStructs.h', archive]}
    (results / 'native-probe-identities.json').write_text(json.dumps(dict(
        revision=git('rev-parse', 'HEAD', cwd=native), hashes=hashes,
        compiler=subprocess.check_output(['g++', '--version'], text=True)), indent=2) + '\n')
    return probe, junit, archive


def test_jfr(variant, tree, prepared):
    probe, junit, archive = prepared
    modules = os.pathsep.join(str(cache / variant / 'compiler/dists' / f)
                             for f in ['graal.jar', 'graal-management.jar', 'graal-options.jar'])
    binary = results / ('jfr-' + variant)
    args = ['native-image', '--verbose', '-ea', '-esa', '-J-ea', '-J-esa', '--parallelism=8', '-J-Xmx8g',
            '-J--class-path=' + str(probe / 'provider.jar'),
            '-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.jfr=ALL-UNNAMED',
            '-J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core=ALL-UNNAMED',
            '-J--add-exports=org.graalvm.nativeimage.shared/com.oracle.svm.shared.option=ALL-UNNAMED',
            '-J--upgrade-module-path=' + modules, '-H:+UnlockExperimentalVMOptions', '-H:SupportedGCs=G1',
            '--enable-monitoring=jfr', '-H:CLibraryPath=' + str(probe) + ',' + str(archive.parent),
            '-cp', str(probe / 'probe.jar') + os.pathsep + junit, '-o', str(binary), 'review.JfrNativeReviewMain']
    run(variant + '-image', tree, args)
    log = external(variant + '-native-g1', [str(binary), '-Xmx256m', '-Xms32m'], results,
                   expected='missing instance allocation samples' if variant == 'baseline' else None)
    if variant == 'candidate':
        for marker in ['EXACT_CANDIDATE_TEST=PASS', 'RESTART_EXACT_TEST=PASS', 'CONTROLLED_WEIGHT=PASS',
                       'FAILED_ARRAY_AND_NEGATIVE_LENGTH=PASS', 'DISABLED_EVENT=PASS',
                       'DISABLED_STACK=PASS', 'VIRTUAL_ALLOCATION_SAMPLES=PASS']:
            if marker not in log:
                raise RuntimeError('Missing candidate check: ' + marker)
    (results / (variant + '-binary.sha256')).write_text(hashlib.sha256(binary.read_bytes()).hexdigest() + '\n')
    binary.unlink()


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
        if subject['kind'] == 'jfr':
            if variant == 'baseline':
                prepared_jfr = prepare_jfr()
            test_jfr(variant, tree, prepared_jfr)
            continue
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
                command = ['./svmjunit', '-Dnative.test.path=..', '-Dnfi.test.reportCallOverhead=true', '-Dpolyglot.engine.AllowExperimentalOptions=true', '-Dpolyglot.engine.BackgroundCompilation=false',
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
    native_worktree = Path('/build/worktrees/labs-openjdk') / ('value-ci-' + run_id)
    if native_worktree.exists():
        subprocess.run(['git', '-C', str(cache / 'native-git'), 'worktree', 'remove', '--force', str(native_worktree)], check=False)
    shutil.rmtree(cache, ignore_errors=True)
