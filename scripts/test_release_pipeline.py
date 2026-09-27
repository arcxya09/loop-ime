"""Exercise publication failure boundaries without any network calls or real credentials."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-release.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.file = Path(self.temp.name) / 'Loop.apk'
        self.file.write_bytes(b'synthetic-test-apk')
        self.meta = dict(tag='v0.1.12-alpha.13', commit='a' * 40, version='0.1.12-alpha.13', prerelease=True)

    def api(self, **options):
        owner = self
        class Fake:
            def __init__(self):
                self.calls = []
            def call(self, method, path, payload=None, **kwargs):
                self.calls.append((method, path, payload))
                if path.startswith('/git/ref/'):
                    return dict(object=dict(type='commit', sha=('b' if options.get('wrong_tag') else 'a') * 40)) if options.get('tag') else None
                if path.startswith('/releases/tags/'):
                    if options.get('published'):
                        return dict(id=1, draft=False, html_url='https://github.com/test/loop/releases/tag/test')
                    return None
                if method == 'POST' and path == '/releases':
                    return dict(id=1, draft=True, upload_url='https://uploads.github.com/repos/test/loop/releases/1/assets{?name,label}')
                if path == '/releases/1/assets?per_page=100':
                    return [dict(name=owner.file.name, state='uploaded')] if options.get('published') else []
                if path.startswith('https://uploads.github.com/'):
                    if options.get('upload_fails'):
                        raise RuntimeError('HTTP 502')
                    return dict(state='uploaded', size=owner.file.stat().st_size,
                        digest='sha256:' + ('0' * 64 if options.get('bad_digest') else hashlib.sha256(owner.file.read_bytes()).hexdigest()))
                if method == 'PATCH' and path == '/releases/1':
                    return dict(draft=False, html_url='https://github.com/test/loop/releases/tag/test')
                raise AssertionError((method, path))
        return Fake()

    def test_every_asset_is_verified_before_draft_is_published(self):
        api = self.api()
        url = publisher.publish(api, self.meta, [self.file], 'Release notes')
        self.assertTrue(url.startswith('https://github.com/'))
        writes = [(method, path) for method, path, _ in api.calls if method != 'GET']
        self.assertEqual(['POST', 'POST', 'PATCH'], [method for method, _ in writes])
        self.assertTrue(api.calls[2][2]['draft'])
        self.assertFalse(api.calls[-1][2]['draft'])

    def test_stable_release_is_public_non_preview_and_latest(self):
        self.meta.update(version='0.2.0', tag='v0.2.0', prerelease=False)
        api = self.api()
        publisher.publish(api, self.meta, [self.file], 'Stable release')
        create = next(payload for method, path, payload in api.calls if method == 'POST' and path == '/releases')
        self.assertFalse(create['prerelease'])
        self.assertEqual('true', create['make_latest'])
        self.assertFalse(api.calls[-1][2]['prerelease'])
        self.assertEqual('true', api.calls[-1][2]['make_latest'])

    def test_new_packages_require_stable_semantic_version(self):
        spec = importlib.util.spec_from_file_location('packager', Path(__file__).with_name('package-release.py'))
        packager = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(packager)
        for version in ('0.2.0', '0.2.1', '0.10.0', '1.0.0'):
            packager.validate_release_version(version)
        for version in ('0.1.21-alpha.22', '0.2.0-alpha.1', '0.2.0-rc.1', '0.2', '0.1.99'):
            with self.assertRaises(ValueError):
                packager.validate_release_version(version)

    def test_failed_upload_or_bad_server_hash_never_publishes(self):
        for option in ('upload_fails', 'bad_digest'):
            api = self.api(**{option: True})
            with self.assertRaises((RuntimeError, ValueError)):
                publisher.publish(api, self.meta, [self.file], 'Release notes')
            self.assertFalse(any(method == 'PATCH' for method, _, _ in api.calls))

    def test_existing_tag_for_another_commit_stops_all_writes(self):
        api = self.api(tag=True, wrong_tag=True)
        with self.assertRaises(ValueError):
            publisher.publish(api, self.meta, [self.file], 'Release notes')
        self.assertTrue(all(method == 'GET' for method, _, _ in api.calls))

    def test_existing_published_release_is_never_overwritten(self):
        api = self.api(tag=True, published=True)
        publisher.publish(api, self.meta, [self.file], 'Release notes')
        self.assertTrue(all(method == 'GET' for method, _, _ in api.calls))

    def test_unexpected_upload_host_is_rejected_before_network_access(self):
        api = publisher.GitHub('test/loop', 'synthetic-test-token')
        with self.assertRaises(ValueError):
            api.call('POST', 'https://example.com/upload', file=self.file)


if __name__ == '__main__':
    unittest.main()
