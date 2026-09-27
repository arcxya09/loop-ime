import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('materialize', Path(__file__).with_name('materialize-dependencies.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MaterializeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.scope = patch.object(module, 'ROOT', self.root)
        self.scope.start()
        self.addCleanup(self.scope.stop)
        data = b'first\x00second\xff'
        parts = []
        for i, piece in enumerate((data[:5], data[5:])):
            name = f'{i}.part'
            (self.root / name).write_bytes(piece)
            parts.append(dict(path=name, sha256=hashlib.sha256(piece).hexdigest()))
        self.entry = dict(path='lib/data.bin', size=len(data), sha256=hashlib.sha256(data).hexdigest(), parts=parts)
        self.data = data

    def test_exact_reassembly_and_repeat_are_idempotent(self):
        module.restore(self.entry)
        module.restore(self.entry)
        self.assertEqual(self.data, (self.root / 'lib/data.bin').read_bytes())

    def test_corrupt_chunk_does_not_leave_a_partial_dependency(self):
        (self.root / '1.part').write_bytes(b'bad')
        with self.assertRaises(ValueError):
            module.restore(self.entry)
        self.assertEqual([], list((self.root / 'lib').iterdir()))

    def test_existing_modified_dependency_and_outside_paths_are_preserved(self):
        (self.root / 'lib').mkdir()
        target = self.root / 'lib/data.bin'
        target.write_bytes(b'user change')
        with self.assertRaises(ValueError):
            module.restore(self.entry)
        self.assertEqual(b'user change', target.read_bytes())
        with self.assertRaises(ValueError):
            module.contained('../outside.bin')


if __name__ == '__main__':
    unittest.main()
