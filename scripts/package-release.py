#!/usr/bin/env python3
"""Verify a signed APK and passing tests, then package the exact committed source."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SIGNER = '8ce80c674b10d7a75f2262e711337f1fd53c6b23e9554fe04fab1bd8a4ae6bcb'


def run(args):
    return subprocess.check_output(args, cwd=ROOT, text=True, stderr=subprocess.STDOUT)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def validate_signature_report(report, require_all_schemes=False):
    """Require the original single signer; compatibility checks must verify v1, v2 and v3."""
    certificates = re.findall(r'^.*certificate SHA-256 digest: ([0-9a-f]{64})\s*$', report, re.M)
    if not certificates or set(certificates) != {SIGNER} or not re.search(r'^Number of signers: 1\s*$', report, re.M):
        raise ValueError('APK signer changed or is ambiguous; updates must retain the original certificate.')
    schemes = set(re.findall(r'^Verified using (v[123]) scheme [^\r\n]*: true\s*$', report, re.M))
    if require_all_schemes and schemes != {'v1', 'v2', 'v3'}:
        raise ValueError('APK must verify with v1, v2 and v3; a v2-only package is not sufficient for installer compatibility.')
    return sorted(schemes)


def validate_release_version(version):
    if not re.fullmatch(r'\d+\.\d+\.\d+', version) or tuple(map(int, version.split('.'))) < (0, 2, 0):
        raise ValueError('From 0.2.0 onward, publish stable semantic versions only; no alpha/beta/rc suffix.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--test-results', required=True, type=Path)
    parser.add_argument('--out', default=Path('dist'), type=Path)
    args = parser.parse_args()
    source = (ROOT / 'app/build.gradle.kts').read_text()
    version = re.search(r'versionName\s*=\s*"([0-9A-Za-z.+-]+)"', source)[1]
    validate_release_version(version)
    code = int(re.search(r'versionCode\s*=\s*(\d+)', source)[1])
    commit = run(['git', 'rev-parse', 'HEAD']).strip()
    if run(['git', 'status', '--porcelain', '--untracked-files=no']).strip():
        raise SystemExit('Tracked source has uncommitted changes; commit before packaging.')
    build = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', ''))) / 'build-tools/37.0.0'
    signature = run([str(build / 'apksigner'), 'verify', '--verbose', '--print-certs', str(args.apk.resolve())])
    validate_signature_report(signature)
    # The APK still requires API 37. The wider verifier range exercises all three signature
    # formats, including the JAR certificate lookup used by some installer inspection paths.
    compatibility = run([str(build / 'apksigner'), 'verify', '--verbose', '--print-certs',
        '--min-sdk-version', '23', '--max-sdk-version', '37', str(args.apk.resolve())])
    schemes = validate_signature_report(compatibility, require_all_schemes=True)
    manifest = run([str(build / 'aapt2'), 'dump', 'badging', str(args.apk.resolve())])
    required = ["name='app.loop.ime'", f"versionName='{version}'", f"versionCode='{code}'", "minSdkVersion:'37'", "targetSdkVersion:'37'"]
    if not all(item in manifest for item in required) or 'application-debuggable' in manifest:
        raise SystemExit('APK package/version/SDK does not match this source, or APK is debuggable.')
    run([str(build / 'zipalign'), '-c', '-P', '16', '4', str(args.apk.resolve())])
    libraries = 0
    with zipfile.ZipFile(args.apk) as apk:
        if apk.testzip():
            raise SystemExit('Corrupt APK ZIP.')
        for name in apk.namelist():
            if name.startswith('lib/') and name.endswith('.so'):
                raw = apk.read(name)
                if raw[:6] != b'\x7fELF\x02\x01':
                    raise SystemExit(f'Unsupported native ELF: {name}')
                phoff = struct.unpack_from('<Q', raw, 32)[0]
                size, count = struct.unpack_from('<HH', raw, 54)
                for index in range(count):
                    fields = struct.unpack_from('<IIQQQQQQ', raw, phoff + index * size)
                    if fields[0] == 1 and (fields[7] < 16384 or (fields[2] - fields[3]) % 16384):
                        raise SystemExit(f'Native library lacks 16 KB alignment: {name}')
                libraries += 1
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in args.test_results.glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, 0))
    if not totals['tests'] or totals['failures'] or totals['errors']:
        raise SystemExit('Passing JUnit results are required before release.')
    out = args.out.resolve()
    if out.exists() and any(out.iterdir()):
        raise SystemExit('Output directory is not empty; use a fresh directory.')
    out.mkdir(parents=True, exist_ok=True)
    apk_out = out / f'Loop-IME-{version}.apk'
    shutil.copy2(args.apk, apk_out)
    source_out = out / f'Loop-IME-{version}-source.zip'
    run(['git', 'archive', '--format=zip', '--prefix=LoopIME/', '-o', str(source_out), commit])
    notes = (ROOT / 'CHANGELOG.md').read_text()
    match = re.search(r'^## ' + re.escape(version) + r'[^\n]*\n(.*?)(?=^## |\Z)', notes, re.M | re.S)
    if not match:
        raise SystemExit('Current version has no CHANGELOG entry.')
    body = f'# Loop 输入法 {version}\n\n' + match[1].strip()
    body += f'\n\n本次构建：{totals["tests"]} 项主机测试，{totals["skipped"]} 项跳过；Android 17 / API 37+。\n'
    body += f'源码提交：`{commit}`。沿用原开发签名，支持从 Alpha 覆盖更新。\n'
    body += 'APK 与源码 SHA-256 见附件。真机、真实账号及旧库恢复的验证边界见源码 TESTING.md。\n'
    (out / 'release-notes.md').write_text(body)
    metadata = dict(version=version, version_code=code, tag=f'v{version}', commit=commit,
                    prerelease=False, tests=totals, native_libraries=libraries,
                    signer_sha256=SIGNER,
                    signature_schemes=schemes,
                    assets={p.name: dict(bytes=p.stat().st_size, sha256=digest(p)) for p in (apk_out, source_out)})
    (out / 'release-manifest.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    checks = [apk_out, source_out, out / 'release-manifest.json', out / 'release-notes.md']
    (out / 'SHA256SUMS.txt').write_text(''.join(f'{digest(p)}  {p.name}\n' for p in checks))
    print(json.dumps(dict(version=version, commit=commit, tests=totals, output=str(out)), ensure_ascii=False))


if __name__ == '__main__':
    main()
