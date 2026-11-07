import pathlib
import subprocess
import sys
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).with_name("prepare_fork_android.py").resolve()


class ForkAndroidProfile(unittest.TestCase):
    def test_optimized_release_keeps_the_installed_test_identity_and_signer(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            path = root / "apps/player-android/app/build.gradle.kts"
            path.parent.mkdir(parents=True)
            path.write_text('''android {
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("production")
        }
    }
}
''')
            subprocess.run([sys.executable, str(SCRIPT)], cwd=root, check=True)
            prepared = path.read_text()
            self.assertEqual(prepared.count('applicationIdSuffix = ".test"'), 2)
            self.assertIn('isMinifyEnabled = true', prepared)
            self.assertIn('signingConfig = signingConfigs.getByName("debug")', prepared)
            self.assertNotIn('findByName("production")', prepared)
