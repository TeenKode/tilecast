import unittest

from android_network_resource import network_resource_path


class NetworkResourcePath(unittest.TestCase):
    def test_source_and_optimized_paths(self):
        for path in ["res/xml/tilecast_network_security_config.xml", "res/a9.xml"]:
            table = f'''      resource 0x7f140009 org.tilecast.player.test:xml/tilecast_network_security_config: t=0x03 d=0x00000003 (s=0x0008 r=0x00)
        (string8) "{path}"
      resource 0x7f14000a org.tilecast.player.test:xml/other: t=0x03
        (string8) "res/other.xml"
'''
            self.assertEqual(network_resource_path(table), path)

    def test_missing_resource_cannot_use_an_unrelated_policy(self):
        with self.assertRaises(ValueError):
            network_resource_path('resource 0x7f14000a org.tilecast.player.test:xml/other: t=0x03\n (string8) "res/other.xml"')
