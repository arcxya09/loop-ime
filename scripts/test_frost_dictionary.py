"""Offline integrity and wiring checks for the pinned, verbatim Frost tables."""
import hashlib
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
RIME = ROOT / 'app/src/main/assets/rime'


class FrostDictionaryTest(unittest.TestCase):
    def test_pinned_tables_are_complete_and_unmodified(self):
        manifest = json.loads((ROOT / 'third_party/rime-frost.json').read_text())
        self.assertEqual('3ad2cb34e3c5763ba3f8da0a617fcaa221b355aa', manifest['commit'])
        for entry in manifest['files']:
            raw = (ROOT / entry['path']).read_bytes()
            self.assertEqual(entry['bytes'], len(raw))
            self.assertEqual(entry['sha256'], hashlib.sha256(raw).hexdigest())
        self.assertGreater(sum(e['entries'] for e in manifest['files']), 650000)

    def test_both_layouts_use_the_same_frost_dictionary(self):
        for name in ('loop_t9', 'luna_pinyin_simp'):
            self.assertIn('dictionary: loop_frost', (RIME / f'{name}.schema.yaml').read_text(encoding='utf-8'))
        source = (RIME / 'loop_frost.dict.yaml').read_text(encoding='utf-8')
        imports = re.findall(r'^  - (cn_dicts/\w+)$', source, re.M)
        self.assertEqual(['cn_dicts/8105', 'cn_dicts/41448', 'cn_dicts/base', 'cn_dicts/ext'], imports)
        for name in imports:
            self.assertTrue((RIME / f'{name}.dict.yaml').is_file())
        self.assertIn('use_preset_vocabulary: false', source)

    def test_common_modern_words_have_explicit_pronunciation_and_frequency(self):
        content = (RIME / 'cn_dicts/base.dict.yaml').read_text(encoding='utf-8')
        for word, pinyin in [('输入法', 'shu ru fa'), ('人工智能', 'ren gong zhi neng'), ('计算机', 'ji suan ji')]:
            self.assertRegex(content, rf'(?m)^{word}\t{pinyin}\t\d+$')


if __name__ == '__main__':
    unittest.main()
