"""Stage exact qualified SDK/Maven bytes for a separately authorized publication."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.error
import urllib.request
from producer_artifact import download_artifact

SOURCE = 'bf8d89875e82a9328950359f19a1e46486d2417f'
VERSION = '25.5.5-elide.25'
PUBLIC_BASE = 'https://maven.elide.dev/gvm-elide/'


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def relative(name):
    path = Path(name)
    if path.is_absolute() or '..' in path.parts or '\\' in name:
        raise ValueError('Expected a relative release member')
    return path


def download(package, destination):
    if type(package['run']) is not int or not re.fullmatch('[0-9a-f]{40}', package['commit']):
        raise ValueError('Expected an exact producer run and commit')
    metadata = json.loads(subprocess.check_output([
        'gh', 'run', 'view', str(package['run']), '-R', 'elide-dev/graal',
        '--json', 'headSha,conclusion'], text=True))
    if metadata['headSha'] != package['commit'] or metadata['conclusion'] != 'success':
        raise ValueError('Expected the exact successful producer build')
    download_artifact(package, 'elide-dev/graal', destination)


def stage(catalog_path, output):
    catalog = json.loads(catalog_path.read_text())
    maven = catalog['linux-amd64']['maven']
    downloaded = output.parent / 'producer-downloads'
    repository = downloaded / 'maven'
    download(maven, repository)
    manifest_path = repository / 'manifest.json'
    if sha(manifest_path) != maven['sha256']:
        raise ValueError('Maven manifest differs from the reviewed checksum')
    manifest = json.loads(manifest_path.read_text())
    if manifest['source'] != SOURCE or manifest['version'] != VERSION:
        raise ValueError('Expected the qualified repair source and version')
    platforms = {'linux-amd64': 'linux-amd64', 'linux-arm64': 'linux-aarch64',
                 'macos-arm64': 'macos-aarch64', 'windows-amd64': 'windows-amd64'}
    if manifest['runs'] != {archive: catalog[platform]['sdk']['run'] for platform, archive in platforms.items()}:
        raise ValueError('Maven and SDK packages do not share the same build cohort')
    for name, expected in manifest['files'].items():
        if sha(repository / 'repository' / relative(name)) != expected:
            raise ValueError('Maven bytes differ from the qualified manifest: ' + name)
    target = output / VERSION
    shutil.copytree(repository / 'repository', target)
    dist = target / 'dist'
    dist.mkdir()
    for platform in platforms:
        package = catalog[platform]['sdk']
        if package['commit'] != SOURCE:
            raise ValueError('Expected the same qualified SDK producer on every platform')
        folder = downloaded / platform
        download(package, folder)
        for member, digest in [('file', 'sha256'), ('ceFile', 'ceSha256')]:
            name = package[member]
            if Path(name).name != name or VERSION not in name:
                raise ValueError('Expected a qualified SDK archive basename')
            archive, checksum = folder / name, folder / (name + '.sha256')
            if sha(archive) != package[digest] or checksum.read_text().split()[0] != package[digest]:
                raise ValueError('SDK bytes differ from the reviewed pin: ' + name)
            shutil.copyfile(archive, dist / name)
            shutil.copyfile(checksum, dist / checksum.name)
    files = {path.relative_to(target).as_posix(): sha(path)
             for path in sorted(target.rglob('*')) if path.is_file()}
    output.joinpath('manifest.json').write_text(json.dumps({
        'source': SOURCE, 'version': VERSION, 'catalogSha256': sha(catalog_path),
        'mavenProducer': maven, 'sdkRuns': manifest['runs'], 'files': files}, indent=2) + '\n')
    print('Staged exact release bytes:', VERSION, len(files), 'files')
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
            stream.write('version=' + VERSION + '\n')


def verify(output, require_unpublished=False, remote=False):
    manifest = json.loads(output.joinpath('manifest.json').read_text())
    if manifest['source'] != SOURCE or manifest['version'] != VERSION:
        raise ValueError('Unexpected staged release')
    if require_unpublished:
        url = PUBLIC_BASE + VERSION + '/org/graalvm/polyglot/polyglot/' + VERSION + '/polyglot-' + VERSION + '.pom'
        try:
            with urllib.request.urlopen(url, timeout=60):
                raise ValueError('Version is already published; immutable releases must not be overwritten')
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
    def check(item):
        name, expected = item
        path = relative(name)
        if remote:
            digest = hashlib.sha256()
            with urllib.request.urlopen(PUBLIC_BASE + VERSION + '/' + path.as_posix(), timeout=120) as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(block)
            actual = digest.hexdigest()
        else:
            actual = sha(output / VERSION / path)
        if actual != expected:
            raise ValueError('Release bytes differ from the qualified stage: ' + name)
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(check, manifest['files'].items()))
    print('Verified', 'published' if remote else 'staged', 'release bytes:', VERSION)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['stage', 'verify', 'verify-remote'])
    parser.add_argument('output', type=Path)
    parser.add_argument('--catalog', type=Path)
    parser.add_argument('--require-unpublished', action='store_true')
    args = parser.parse_args()
    if args.mode == 'stage':
        if args.catalog is None:
            parser.error('stage requires --catalog')
        stage(args.catalog, args.output)
    else:
        verify(args.output, args.require_unpublished, args.mode == 'verify-remote')


if __name__ == '__main__':
    main()
