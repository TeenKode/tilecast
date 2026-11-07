import unittest

import release_version as rv


class ForkVersions(unittest.TestCase):
    def test_dev_is_beta_with_increasing_codes(self):
        names = ["1.0.0", "1.0.1-dev.1", "1.0.1-dev.2", "1.0.1-dev.98", "1.0.1"]
        codes = [rv.version_code(name) for name in names]
        self.assertEqual(codes, sorted(set(codes)))
        self.assertEqual(rv.parse_tag("v1.0.1-dev.1").channel, "beta")

    def test_invalid_dev_slots(self):
        for name in ["1.0.1-dev", "1.0.1-dev.0", "1.0.1-dev.99", "1.0.1-dev.01"]:
            self.assertIsNone(rv.parse_version(name), name)
