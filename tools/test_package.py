import unittest
from pathlib import Path
if __package__:
    from .package import included, archive_path_allowed
else:
    from package import included, archive_path_allowed


class PackageTests(unittest.TestCase):
    def test_private_files_excluded(self):
        for path in ["cookie.txt", "node.cookies.txt", ".env", ".env.local", "release.jks", ".secrets/cookie.txt", "androidApp/build/outputs/apk/debug/app.apk", "local.properties", ".gradle/cache.bin", "tools/__pycache__/x.pyc", "artifacts/source.zip"]:
            self.assertFalse(included(Path(path)), path)

    def test_source_and_wrapper_included(self):
        for path in ["gradle/wrapper/gradle-wrapper.jar", "gradlew", "shared/src/commonMain/kotlin/App.kt", ".github/workflows/android.yml", "config/domains.json", "README.md"]:
            self.assertTrue(included(Path(path)), path)
        metadata = "META-INF/com/android/build/gradle/app-metadata.properties"
        self.assertTrue(archive_path_allowed(metadata))
        self.assertFalse(archive_path_allowed(metadata, source=True))
        self.assertFalse(archive_path_allowed(".secrets/cookie.txt"))
        self.assertFalse(archive_path_allowed("assets/cookie.txt"))
        self.assertFalse(archive_path_allowed("assets/node.cookies.txt"))


if __name__ == "__main__":
    unittest.main()
