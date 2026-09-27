#!/usr/bin/env python3
"""Device checks on an explicitly selected Android 17+ test device; never uninstall or clear data."""
import argparse
import json
import subprocess
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--tests', required=True, type=Path)
    parser.add_argument('--upgrade-tests', type=Path, help='APK built with -PloopTestRunner=app.loop.ime.UpgradeInstrumentation')
    parser.add_argument('--baseline', type=Path, help='Optional delivered versionCode 10 APK; requires empty app storage')
    parser.add_argument('--timeout-scale', type=int, choices=range(1,11), default=1,
                        help='Native concurrency timeout multiplier for software emulation')
    parser.add_argument('--out', type=Path, default=Path('verification/device-check'))
    args = parser.parse_args()
    if args.baseline and not args.upgrade_tests:
        parser.error('--baseline requires --upgrade-tests')
    for path in (args.apk, args.tests, args.baseline, args.upgrade_tests):
        if path is not None and not path.is_file():
            parser.error(f'File does not exist: {path}')
    args.out.mkdir(parents=True, exist_ok=True)
    results = []

    def run(name, command, expected=None, timeout=180):
        try:
            proc = subprocess.run([args.adb, '-s', args.serial, *command],
                                  capture_output=True, text=True, timeout=timeout)
            output = proc.stdout + proc.stderr
            passed = proc.returncode == 0 and (expected is None or expected in output)
            record = {'name': name, 'exit': proc.returncode, 'passed': passed, 'output': output}
        except subprocess.TimeoutExpired as error:
            output = error.stdout or b''
            if isinstance(output, bytes):
                output = output.decode(errors='replace')
            record = {'name': name, 'exit': 'timeout', 'passed': False, 'output': output}
        results.append(record)
        (args.out / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n')
        print(('PASS ' if record['passed'] else 'FAIL ') + name, flush=True)
        if not record['passed']:
            raise RuntimeError(f'{name} failed; see {args.out / "results.json"}')
        return record['output'].strip()

    def instrument(name, runner, *parameters):
        run(name, ['shell', 'am', 'instrument', '-w', '-r', '-e', 'timeoutScale', str(args.timeout_scale),
                   *parameters, f'app.loop.ime.test/app.loop.ime.{runner}'],
            expected='INSTRUMENTATION_RESULT: result=PASS', timeout=600)

    try:
        api = run('Android API', ['shell', 'getprop', 'ro.build.version.sdk'])
        if not api.isdigit() or int(api) < 37:
            raise RuntimeError('Requires Android 17 / API 37+')
        run('Android boot completed', ['shell', 'getprop', 'sys.boot_completed'], expected='1')
        # boot_completed remains 1 when system_server crashes after the initial boot.
        run('package service ready', ['shell', 'service', 'check', 'package'], expected='Service package: found')
        run('activity service ready', ['shell', 'service', 'check', 'activity'], expected='Service activity: found')
        run('page size', ['shell', 'getconf', 'PAGE_SIZE'])
        if args.baseline:
            run('install baseline', ['install', '-r', str(args.baseline)], expected='Success')
            run('install upgrade instrumentation', ['install', '-r', str(args.upgrade_tests)], expected='Success')
            instrument('seed isolated v1 database and keys', 'UpgradeInstrumentation', '-e', 'phase', 'seed')
            run('stop baseline process', ['shell', 'am', 'force-stop', 'app.loop.ime'])
        run('install delivered APK', ['install', '-r', str(args.apk)], expected='Success')
        if args.baseline:
            instrument('read data after APK upgrade', 'UpgradeInstrumentation', '-e', 'phase', 'readback')
            run('stop upgraded process', ['shell', 'am', 'force-stop', 'app.loop.ime'])
            instrument('read upgraded data after process restart', 'UpgradeInstrumentation', '-e', 'phase', 'readback')
        run('install instrumentation for delivered APK', ['install', '-r', str(args.tests)], expected='Success')
        instrument('native database and keystore', 'LoopInstrumentation', '-e', 'only', 'database')
        run('stop database test process', ['shell', 'am', 'force-stop', 'app.loop.ime'])
        instrument('read database after process restart', 'LoopInstrumentation', '-e', 'only', 'database_readback')
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1
    print('All requested device checks passed.', flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
