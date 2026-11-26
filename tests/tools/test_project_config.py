"""Configuration and migration regression tests; temporary data only."""
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from project_config import ROOT, data_path, load_paths, read_env
from migrate_data import migration_moves


class ProjectConfigTests(unittest.TestCase):
    def test_environment_overrides_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text("# synthetic\nAI_LLM_API_KEY=file-key\nTRANSLATE_LLM_API_KEY=other-key\n")
            with patch.dict(os.environ, {"AI_LLM_API_KEY": "process-key"}, clear=True):
                self.assertEqual(read_env(path), {
                    "AI_LLM_API_KEY": "process-key", "TRANSLATE_LLM_API_KEY": "other-key",
                })

    def test_paths_do_not_depend_on_working_directory(self):
        before = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                self.assertEqual(data_path("collection"), ROOT / ".data/crawl/collection")
            finally:
                os.chdir(before)

    def test_invalid_path_configuration_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "paths.yaml"
            path.write_text('{"collection": null}')
            with self.assertRaises(ValueError):
                load_paths(path)

    def test_migration_conflict_is_detected_before_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".data/collection").mkdir(parents=True)
            (root / ".data/crawl/collection").mkdir(parents=True)
            with self.assertRaises(ValueError):
                migration_moves(root)
            self.assertTrue((root / ".data/collection").exists())

    def test_migration_preserves_checkpoint_and_is_idempotent(self):
        import shutil
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".data/crawl").mkdir(parents=True)
            (root / ".data/crawl/state.json").write_text('{"requests": 42}')
            for source, target in migration_moves(root):
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(source), str(target))
            self.assertEqual((root / ".data/crawl/checkpoint/state.json").read_text(), '{"requests": 42}')
            self.assertEqual(migration_moves(root), [])


if __name__ == "__main__":
    unittest.main()