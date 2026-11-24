"""Unavailable link privileges skip explicitly; unrelated failures remain visible."""
import unittest
from pathlib import Path
from unittest.mock import patch

from test_support.filesystem import symlink_or_skip


class SymlinkSupportTests(unittest.TestCase):
    def test_directory_flag_and_paths_are_preserved(self):
        with patch.object(Path, "symlink_to") as create:
            symlink_or_skip(self, Path("link"), Path("target"), target_is_directory=True)
        create.assert_called_once_with(Path("target"), target_is_directory=True)

    def test_absent_windows_privilege_is_an_explicit_skip(self):
        denied = OSError("symlink privilege is unavailable")
        denied.winerror = 1314
        with patch.object(Path, "symlink_to", side_effect=denied), \
                self.assertRaisesRegex(unittest.SkipTest, "not permitted"):
            symlink_or_skip(self, Path("link"), Path("target"))

    def test_unimplemented_links_are_an_explicit_skip(self):
        with patch.object(Path, "symlink_to", side_effect=NotImplementedError), \
                self.assertRaisesRegex(unittest.SkipTest, "unavailable"):
            symlink_or_skip(self, Path("link"), Path("target"))

    def test_unexpected_filesystem_errors_are_not_skipped(self):
        with patch.object(Path, "symlink_to", side_effect=FileExistsError("link exists")), \
                self.assertRaises(FileExistsError):
            symlink_or_skip(self, Path("link"), Path("target"))
