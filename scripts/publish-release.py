#!/usr/bin/env python3
"""Publish a verified bundle; keep incomplete uploads in a draft and never replace a published version."""
import argparse
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GitHub:
    def __init__(self, repo, token):
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
            raise ValueError('Specify the repository as owner/name.')
        if not token:
            raise ValueError('GitHub write authentication is required; no token was provided.')
        self.root = f'https://api.github.com/repos/{repo}'
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def call(self, method, path, payload=None, *, missing=False, file=None):
        url = path if path.startswith('https://') else self.root + path
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != 'https' or parsed.hostname not in ('api.github.com', 'uploads.github.com'):
            raise ValueError('Refusing to send GitHub credentials to an unexpected host.')
        headers = {'Authorization': 'Bearer ' + self.token, 'Accept': 'application/vnd.github+json',
                   'X-GitHub-Api-Version': '2026-03-10', 'User-Agent': 'Loop-IME-release'}
        data = None if payload is None else json.dumps(payload).encode()
        stream = None
        if data is not None:
            headers['Content-Type'] = 'application/json'
        if file is not None:
            stream = file.open('rb')
            data = stream
            headers['Content-Type'] = mimetypes.guess_type(file.name)[0] or 'application/octet-stream'
            headers['Content-Length'] = str(file.stat().st_size)
        try:
            request = urllib.request.Request(url, data=data, headers=headers, method=method)
            with self.opener.open(request, timeout=60) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            if missing and error.code == 404:
                return None
            raise RuntimeError(f'GitHub {method} failed with HTTP {error.code}; release remains unpublished if upload was incomplete.') from None
        finally:
            if stream is not None:
                stream.close()


def checked_bundle(folder):
    folder = folder.resolve()
    expected = {}
    for line in (folder / 'SHA256SUMS.txt').read_text().splitlines():
        match = re.fullmatch(r'([a-f0-9]{64})  ([A-Za-z0-9._+-]+)', line)
        if not match:
            raise ValueError('Malformed checksum entry.')
        checksum, name = match.groups()
        file = folder / name
        if name in expected or file.is_symlink() or not file.is_file():
            raise ValueError('Missing, duplicate, or unsafe asset.')
        with file.open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != checksum:
                raise ValueError(f'Checksum failed: {name}')
        expected[name] = checksum
    metadata = json.loads((folder / 'release-manifest.json').read_text())
    version = metadata['version']
    if not re.fullmatch(r'[0-9A-Za-z.+-]+', version) or metadata['tag'] != 'v' + version:
        raise ValueError('Invalid release version.')
    if not re.fullmatch(r'[a-f0-9]{40}', metadata['commit']):
        raise ValueError('Release must identify a full source commit.')
    required = {f'Loop-IME-{version}.apk', f'Loop-IME-{version}-source.zip', 'release-manifest.json', 'release-notes.md'}
    if set(expected) != required:
        raise ValueError('Release bundle must contain exactly the expected verified deliverables.')
    tests = metadata['tests']
    if tests['tests'] <= 0 or tests['failures'] or tests['errors']:
        raise ValueError('Tests did not pass.')
    for name, info in metadata['assets'].items():
        if name not in required or expected[name] != info['sha256'] or (folder / name).stat().st_size != info['bytes']:
            raise ValueError('Manifest asset mismatch.')
    return metadata, [folder / name for name in sorted(required | {'SHA256SUMS.txt'})]


def publish(api, metadata, files, notes):
    tag = metadata['tag']
    commit = metadata['commit']
    marker = f'<!-- loop-release:{commit} -->'
    ref = api.call('GET', '/git/ref/tags/' + urllib.parse.quote(tag, safe=''), missing=True)
    if ref:
        obj = ref['object']
        for _ in range(8):
            if obj['type'] == 'commit':
                break
            if obj['type'] != 'tag':
                raise ValueError('Unexpected tag object.')
            obj = api.call('GET', '/git/tags/' + obj['sha'])['object']
        if obj['type'] != 'commit' or obj['sha'] != commit:
            raise ValueError('This version tag belongs to another commit; increase the app version.')
    release = api.call('GET', '/releases/tags/' + urllib.parse.quote(tag, safe=''), missing=True)
    required = {file.name for file in files}
    if release and not release['draft']:
        assets = api.call('GET', f'/releases/{release["id"]}/assets?per_page=100')
        if not ref or not required.issubset({a['name'] for a in assets if a['state'] == 'uploaded'}):
            raise ValueError('Published release has missing assets or tag; it will not be overwritten.')
        return release['html_url']
    if release:
        if marker not in (release.get('body') or '') or release['target_commitish'] != commit:
            raise ValueError('Existing draft is not from this source commit; it will not be modified.')
    else:
        release = api.call('POST', '/releases', dict(tag_name=tag, target_commitish=commit,
            name='Loop 输入法 ' + metadata['version'], body=notes + '\n\n' + marker,
            draft=True, prerelease=metadata['prerelease'], make_latest='false' if metadata['prerelease'] else 'legacy'))
    release_id = release['id']
    assets = {a['name']: a for a in api.call('GET', f'/releases/{release_id}/assets?per_page=100')}
    for file in files:
        existing = assets.get(file.name)
        with file.open('rb') as stream:
            digest = 'sha256:' + hashlib.file_digest(stream, 'sha256').hexdigest()
        if existing and existing['state'] == 'uploaded' and existing.get('digest') == digest:
            continue
        if existing:
            raise ValueError('Draft asset differs from this build; rerun the failed release job with its original bundle, or use a new version. Existing assets are not overwritten.')
        url = release['upload_url'].split('{', 1)[0] + '?' + urllib.parse.urlencode({'name': file.name})
        uploaded = api.call('POST', url, file=file)
        if uploaded['state'] != 'uploaded' or uploaded['size'] != file.stat().st_size or uploaded.get('digest') != digest:
            raise ValueError('Uploaded asset did not pass size/hash verification; keeping draft unpublished.')
    completed = api.call('PATCH', f'/releases/{release_id}', dict(draft=False,
        make_latest='false' if metadata['prerelease'] else 'legacy'))
    if completed['draft']:
        raise ValueError('GitHub did not publish the release.')
    return completed['html_url']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--repo', default=os.environ.get('GITHUB_REPOSITORY'))
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    metadata, files = checked_bundle(args.bundle)
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    if head != metadata['commit']:
        raise SystemExit('Release bundle belongs to another commit.')
    if args.dry_run:
        print(json.dumps(dict(tag=metadata['tag'], commit=head, repository=args.repo,
            prerelease=metadata['prerelease'], assets=[p.name for p in files], network_used=False), ensure_ascii=False))
        return
    if not args.repo:
        raise SystemExit('Repository is not configured. Supply --repo owner/name.')
    api = GitHub(args.repo, os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN'))
    url = publish(api, metadata, files, (args.bundle / 'release-notes.md').read_text())
    print(url)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            summary.write(f'Published [{metadata["tag"]}]({url})\n')


if __name__ == '__main__':
    main()
