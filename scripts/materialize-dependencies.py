#!/usr/bin/env python3
"""Restore exact vendored files from GitHub API sized chunks; never download code."""
import hashlib
import json
from pathlib import Path
import os
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def contained(relative):
    path = (ROOT / relative).resolve()
    if not path.is_relative_to(ROOT):
        raise ValueError('Dependency path leaves repository')
    return path


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def restore(entry):
    target = contained(entry['path'])
    if target.exists():
        if target.stat().st_size != entry['size'] or sha256(target) != entry['sha256']:
            raise ValueError(f'Existing dependency differs; preserve and inspect it: {target}')
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    handle = tempfile.NamedTemporaryFile(prefix=target.name + '.', suffix='.assembling', dir=target.parent, delete=False)
    temporary = Path(handle.name)
    try:
        with handle as output:
            for part in entry['parts']:
                source = contained(part['path'])
                if sha256(source) != part['sha256']:
                    raise ValueError(f'Corrupt dependency chunk: {source}')
                with source.open('rb') as stream:
                    while data := stream.read(1024 * 1024):
                        output.write(data)
        if temporary.stat().st_size != entry['size'] or sha256(temporary) != entry['sha256']:
            raise ValueError(f'Restored dependency checksum mismatch: {target}')
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    manifest = json.loads((ROOT / 'third_party/multipart.json').read_text(encoding='utf-8'))
    for entry in manifest['files']:
        restore(entry)
        print('Verified ' + entry['path'])
