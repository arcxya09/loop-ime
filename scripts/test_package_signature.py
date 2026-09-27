"""Release gating must reject missing signature schemes and changed certificates."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('packager', Path(__file__).with_name('package-release.py'))
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)


class SignatureReportTest(unittest.TestCase):
    def test_cloud_only_apk_keeps_input_engine_and_database_libraries(self):
        packager.validate_cloud_only_assets(['classes.dex', 'lib/arm64-v8a/libloop_rime.so', 'lib/arm64-v8a/librime_jni.so', 'lib/arm64-v8a/libsqlcipher.so'])

    def test_cloud_only_apk_rejects_offline_runtime_models_and_licenses(self):
        for name in ('lib/arm64-v8a/libsherpa-onnx-jni.so', 'lib/x86_64/libonnxruntime.so', 'assets/asr/tokens.txt', 'assets/encoder.onnx', 'assets/licenses/sherpa-Apache-2.0.txt'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                packager.validate_cloud_only_assets([name])

    def report(self, schemes=('v1', 'v2', 'v3'), signer=None):
        return '\n'.join(['Verifies', 'Number of signers: 1',
            *[f'Verified using {s} scheme (signature): {str(s in schemes).lower()}' for s in ('v1', 'v2', 'v3')],
            f'Signer #1: certificate SHA-256 digest: {signer or packager.SIGNER}']) + '\n'

    def test_original_certificate_and_all_schemes_pass(self):
        self.assertEqual(['v1', 'v2', 'v3'], packager.validate_signature_report(self.report(), True))

    def test_previous_v2_only_configuration_is_blocked(self):
        with self.assertRaises(ValueError):
            packager.validate_signature_report(self.report(('v2',)), True)

    def test_every_required_scheme_must_verify(self):
        for missing in ('v1', 'v2', 'v3'):
            with self.subTest(missing=missing), self.assertRaises(ValueError):
                packager.validate_signature_report(self.report(tuple(s for s in ('v1', 'v2', 'v3') if s != missing)), True)

    def test_wrong_extra_or_missing_certificate_is_blocked(self):
        for report in (self.report(signer='f'*64),
                       self.report() + 'Signer #2: certificate SHA-256 digest: ' + 'e'*64 + '\n',
                       self.report().replace('certificate SHA-256 digest:', 'public key SHA-256 digest:'),
                       self.report().replace('Number of signers: 1', 'Number of signers: 2')):
            with self.subTest(report=report), self.assertRaises(ValueError):
                packager.validate_signature_report(report, True)

    def test_platform_default_report_can_skip_legacy_formats(self):
        self.assertEqual(['v3'], packager.validate_signature_report(self.report(('v3',))))


if __name__ == '__main__':
    unittest.main()
