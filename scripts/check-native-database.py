#!/usr/bin/env python3
"""Run delivered SQLCipher code through Android ART/JNI; does not validate app sandbox/Keystore."""
import argparse
import hashlib
import json
import shlex
import subprocess
import sys
import uuid
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--tests', required=True, type=Path)
    parser.add_argument('--out', type=Path, default=Path('verification/native-database'))
    args = parser.parse_args()
    for path in (args.apk, args.tests):
        if not path.is_file():
            parser.error(f'File does not exist: {path}')
    args.out.mkdir(parents=True, exist_ok=True)
    results = []

    def run(name, command, expected=None, timeout=60):
        try:
            result = subprocess.run([args.adb, '-s', args.serial, *command],
                                    capture_output=True, text=True, timeout=timeout)
            output = result.stdout + result.stderr
            passed = result.returncode == 0 and (expected is None or expected in output)
            record = dict(name=name, exit=result.returncode, passed=passed, output=output)
        except subprocess.TimeoutExpired as error:
            output = error.stdout or b''
            if isinstance(output, bytes):
                output = output.decode(errors='replace')
            record = dict(name=name, exit='timeout', passed=False, output=output)
        results.append(record)
        (args.out / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n')
        print(('PASS ' if record['passed'] else 'FAIL ') + name, flush=True)
        if not record['passed']:
            raise RuntimeError(f'{name} failed; see {args.out / "results.json"}')
        return record['output'].strip()

    try:
        api = run('Android API', ['shell', 'getprop', 'ro.build.version.sdk'])
        if not api.isdigit() or int(api) < 37:
            raise RuntimeError('Requires Android 17 / API 37+')
        abi = run('Android ABI', ['shell', 'getprop', 'ro.product.cpu.abi'])
        if abi not in ('arm64-v8a', 'x86_64'):
            raise RuntimeError('Requires arm64-v8a or x86_64')
        page_size = run('page size', ['shell', 'getconf', 'PAGE_SIZE'])
        payload = args.out / 'payload'
        payload.mkdir(exist_ok=True)
        entries = []
        for source_path, name in ((args.apk, 'app-code.jar'), (args.tests, 'test-code.jar')):
            with zipfile.ZipFile(source_path) as source, zipfile.ZipFile(payload / name, 'w') as target:
                dex_names = [n for n in source.namelist() if '/' not in n and n.endswith('.dex')]
                if 'classes.dex' not in dex_names:
                    raise RuntimeError(f'Missing classes.dex in {source_path.name}')
                for entry in sorted(dex_names):
                    data = source.read(entry)
                    target.writestr(entry, data)
                    entries.append(dict(source=source_path.name, entry=entry, sha256=hashlib.sha256(data).hexdigest()))
        with zipfile.ZipFile(args.apk) as source:
            entry = f'lib/{abi}/libsqlcipher.so'
            data = source.read(entry)
            (payload / 'libsqlcipher.so').write_bytes(data)
            entries.append(dict(source=args.apk.name, entry=entry, sha256=hashlib.sha256(data).hexdigest()))
        with args.apk.open('rb') as source:
            apk_hash = hashlib.file_digest(source, 'sha256').hexdigest()
        device_dir = '/data/local/tmp/loop-sqlcipher-' + uuid.uuid4().hex
        provenance = dict(apk_sha256=apk_hash, api=int(api), abi=abi, page_size=int(page_size),
                          device_dir=device_dir, entries=entries, validates_keystore=False,
                          validates_apk_upgrade=False)
        (args.out / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
        run('copy isolated test payload', ['push', '-Z', str(payload), device_dir], timeout=180)
        command = ' '.join((
            'CLASSPATH=' + shlex.quote(f'{device_dir}/app-code.jar:{device_dir}/test-code.jar'),
            'LD_LIBRARY_PATH=' + shlex.quote(device_dir),
            shlex.join(['app_process', '-Djava.library.path=' + device_dir, '/system/bin',
                        'app.loop.ime.NativeDatabaseProbe', device_dir])))
        run('native database write and migration', ['shell', command + ' write'],
            expected='RESULT PASS write', timeout=720)
        run('native database separate-process readback', ['shell', command + ' readback'],
            expected='RESULT PASS readback', timeout=240)
        run('remove this run\'s temporary fixture', ['shell', 'rm', '-r', device_dir])
    except (RuntimeError, OSError, KeyError, zipfile.BadZipFile) as error:
        print(str(error), file=sys.stderr)
        return 1
    print('Native database checks passed. App installation and Keystore require check-device.py.', flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
