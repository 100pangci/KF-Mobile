"""Package only distributable source/APK; never include private development data."""

import hashlib
import http.cookiejar
import json
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {".secrets", ".gradle", ".kotlin", ".idea", ".vscode", ".fleet", ".vs",
            "build", "artifacts", "captures", "__pycache__", ".git",
            ".externalNativeBuild", ".cxx"}
EXCLUDED_SUFFIXES = {".pyc", ".hprof", ".log", ".apk", ".aab", ".zip", ".iml"}


def included(relative):
    return not (set(relative.parts) & EXCLUDED or private_path(relative)
                or relative.suffix.lower() in EXCLUDED_SUFFIXES)


def private_path(relative):
    name = relative.name.lower()
    return (name in {"local.properties", "login-probe-result.json", "cookie.txt", "keystore.properties", ".env"}
            or name.endswith(".cookies.txt") or name.startswith(".env.")
            or relative.suffix.lower() in {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"})


def private_tokens():
    cookie_path = ROOT / "cookie.txt"
    if not cookie_path.is_file():
        cookie_path = ROOT / ".secrets" / "kf.miaola.work.cookies.txt"
    if not cookie_path.exists():
        return []
    text = cookie_path.read_text(encoding="utf-8")
    if "\t" not in text:
        return [pair.split("=", 1)[1].strip().encode("utf-8")
                for pair in text.removeprefix("Cookie:").strip().split(";")
                if "=" in pair and len(pair.split("=", 1)[1].strip()) >= 16]
    jar = http.cookiejar.MozillaCookieJar()
    jar.load(str(cookie_path), ignore_discard=True, ignore_expires=True)
    return [c.value.encode("utf-8") for c in jar if len(c.value) >= 16]


def archive_path_allowed(name, source=False):
    relative = Path(name)
    if source:
        return included(relative)
    # APKs legitimately contain META-INF/com/android/build/gradle metadata.
    return not (set(relative.parts) & {".secrets", "__pycache__", ".git"} or private_path(relative))


def audit_archive(path, tokens, source=False):
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if not archive_path_allowed(name, source=source):
                raise ValueError("Private/generated path found in distribution")
            content = archive.read(name)
            if any(token in content for token in tokens):
                raise ValueError("Private session value found in distribution")


def tracked_files():
    """Enumerate the repository through Git so ignored local data never enters
    the source archive, even when it is not covered by the hard-coded list."""
    try:
        result = subprocess.run(
            ["git", "-C", str(ROOT), "ls-files", "-z"],
            check=True, capture_output=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return sorted(path for path in ROOT.rglob("*") if path.is_file())
    return [ROOT / os.fsdecode(name) for name in result.stdout.split(b"\0") if name]


def newest_mtime(paths):
    return max((path.stat().st_mtime for path in paths if path.is_file()), default=0.0)


def require_fresh(label, artifact, build_inputs):
    if not artifact.is_file():
        raise ValueError(f"Missing {label}: {artifact.relative_to(ROOT)}")
    if artifact.stat().st_mtime < newest_mtime(build_inputs):
        raise ValueError(
            f"Stale {label}: a build input is newer than {artifact.relative_to(ROOT)}; "
            "rerun the full verification build before packaging"
        )


def lint_errors(path):
    if not path.is_file():
        return []
    root = ET.parse(path).getroot()
    return [issue for issue in root.findall("issue") if issue.get("severity") in {"Error", "Fatal"}]


def main():
    build_script = (ROOT / "androidApp/build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"', build_script)
    if match is None:
        raise ValueError("Missing version name")
    version = match.group(1)
    tests = list((ROOT / "shared/build/test-results/desktopTest").glob("TEST-*.xml"))
    if not tests:
        raise ValueError("Run the test suite before packaging")
    results = [ET.parse(p).getroot() for p in tests]
    if any(int(r.get("failures", 0)) or int(r.get("errors", 0)) for r in results):
        raise ValueError("Refusing to package failing test results")
    if sum(int(r.get("tests", 0)) for r in results) <= 0:
        raise ValueError("Refusing to package an empty test report")
    input_suffixes = {".kt", ".kts", ".xml", ".properties", ".toml"}
    build_inputs = [path for path in tracked_files() if path.suffix.lower() in input_suffixes]
    apk_source = ROOT / "androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    lint_file = ROOT / "androidApp/build/reports/lint-results-debug.xml"
    require_fresh("test results", min(tests, key=lambda p: p.stat().st_mtime), build_inputs)
    require_fresh("debug APK", apk_source, build_inputs)
    require_fresh("lint report", lint_file, build_inputs)
    if lint_errors(lint_file):
        raise ValueError("Refusing to package lint errors")
    lint = ET.parse(lint_file).getroot()
    shared_lint = ROOT / "shared/build/reports/lint-results-debug.xml"
    if lint_errors(shared_lint):
        raise ValueError("Refusing to package shared lint errors")

    output = ROOT / "artifacts"
    output.mkdir(exist_ok=True)
    apk = output / ("KF-Mobile-" + version + "-debug.apk")
    shutil.copy2(apk_source, apk)
    source = output / ("KF-Mobile-" + version + "-source.zip")
    with zipfile.ZipFile(source, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        # Git-tracked files only: local captures, caches and untracked private
        # files are excluded even if the exclusion list misses a new pattern.
        for path in sorted(tracked_files()):
            relative = path.relative_to(ROOT)
            if path.is_file() and included(relative):
                archive.write(path, relative.as_posix())
    tokens = private_tokens()
    audit_archive(apk, tokens)
    audit_archive(source, tokens, source=True)

    previews = output / "previews"
    previews.mkdir(exist_ok=True)
    screenshots = list((ROOT / "shared/build/reports/ui").glob("*.png"))
    for path in screenshots:
        shutil.copy2(path, previews / path.name)
    sums = "".join(hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n" for p in [apk, source])
    (output / "SHA256SUMS.txt").write_text(sums, encoding="ascii")
    summary = {
        "version": version,
        "apk": apk.name,
        "apk_bytes": apk.stat().st_size,
        "source": source.name,
        "tests": sum(int(r.get("tests", 0)) for r in results),
        "test_failures": 0,
        "test_skips": sum(int(r.get("skipped", 0)) for r in results),
        "lint_errors": 0,
        "lint_warnings": sum(i.get("severity") == "Warning" for i in lint.findall("issue")),
        "synthetic_ui_previews": len(screenshots),
        "private_session_value_matches": 0,
        "private_path_matches": 0,
        "note": "Source archive excludes local SDK configuration, caches, build outputs and .secrets. Android device testing remains required.",
    }
    (output / "package-report.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
