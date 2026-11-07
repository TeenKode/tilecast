import unittest

from resolve_fork_version import resolve


def release(version, commit="previous", draft=False):
    return {"tag_name": "v" + version, "target_commitish": commit, "draft": draft}


class ForkVersionSelection(unittest.TestCase):
    def test_first_build_does_not_wait_for_an_upstream_release(self):
        self.assertEqual(resolve("1.0.1-dev.1", [release("1.0.0")], "new"), ("1.0.1-dev.1", False))

    def test_each_published_fork_build_advances(self):
        self.assertEqual(resolve("1.0.1-dev.1", [release("1.0.1-dev.4")], "new"), ("1.0.1-dev.5", False))

    def test_patch_advances_after_slot_98(self):
        self.assertEqual(resolve("1.0.1-dev.1", [release("1.0.1-dev.98")], "new"), ("1.0.2-dev.1", False))

    def test_a_published_commit_is_reused_without_resigning(self):
        self.assertEqual(resolve("1.0.1-dev.1", [release("1.0.1-dev.3", "same")], "same"), ("1.0.1-dev.3", True))

    def test_a_draft_reserves_its_version_for_other_commits(self):
        self.assertEqual(resolve("1.0.1-dev.1", [release("1.0.1-dev.3", draft=True)], "new"), ("1.0.1-dev.4", False))

    def test_a_same_commit_draft_requires_verified_resume(self):
        with self.assertRaises(ValueError):
            resolve("1.0.1-dev.1", [release("1.0.1-dev.3", "same", True)], "same")

    def test_the_minimum_can_start_a_new_series(self):
        self.assertEqual(resolve("1.1.1-dev.1", [release("1.0.1-dev.98")], "new"), ("1.1.1-dev.1", False))

    def test_stable_codes_are_not_reused(self):
        self.assertEqual(resolve("1.0.1-dev.1", [release("1.0.1")], "new"), ("1.0.2-dev.1", False))

    def test_code_ceiling_fails_closed(self):
        with self.assertRaises(ValueError):
            resolve("1.0.1-dev.1", [release("20.999.999-dev.98")], "new")
