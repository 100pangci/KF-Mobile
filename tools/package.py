"""Package only distributable source/APK; never include private development data."""

import hashlib
import http.cookiejar
import json
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {".secrets", ".gradle", ".kotlin", ".idea", "build", "artifacts", "__pycache__", ".git"}


def included(relative):
    return not (set(relative.parts) & EXCLUDED or relative.name in {"local.properties", "login-probe-result.json"}
                or relative.suffix in {".pyc", ".hprof", ".log"})


def private_tokens():
    cookie_path = ROOT / ".secrets" / "kf.miaola.work.cookies.txt"
    if not cookie_path.exists():
        return []
    jar = http.cookiejar.MozillaCookieJar()
    jar.load(str(cookie_path), ignore_discard=True, ignore_expires=True)
    return [c.value.encode("utf-8") for c in jar
            if c.name in {"2ed4e_winduser", "2ed4e_ck_info", "PHPSESSID"} and len(c.value) > 16]


def archive_path_allowed(name, source=False):
    relative = Path(name)
    if source:
        return included(relative)
    # APKs legitimately contain META-INF/com/android/build/gradle metadata.
    return not (set(relative.parts) & {".secrets", "__pycache__", ".git"} or relative.name == "local.properties")


def audit_archive(path, tokens, source=False):
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if not archive_path_allowed(name, source=source):
                raise ValueError("Private/generated path found in distribution")
            content = archive.read(name)
            if any(token in content for token in tokens):
                raise ValueError("Private session value found in distribution")


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
    lint_file = ROOT / "androidApp/build/reports/lint-results-debug.xml"
    lint = ET.parse(lint_file).getroot()
    if any(i.get("severity") in {"Error", "Fatal"} for i in lint.findall("issue")):
        raise ValueError("Refusing to package lint errors")

    output = ROOT / "artifacts"
    output.mkdir(exist_ok=True)
    apk = output / ("KF-Mobile-" + version + "-debug.apk")
    shutil.copy2(ROOT / "androidApp/build/outputs/apk/debug/androidApp-debug.apk", apk)
    source = output / ("KF-Mobile-" + version + "-source.zip")
    with zipfile.ZipFile(source, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(ROOT.rglob("*")):
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
