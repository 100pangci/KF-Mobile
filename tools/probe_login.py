"""Read-only login reconnaissance. Credentials are never persisted or printed."""

import argparse
import getpass
import http.cookiejar
import json
import os
import re
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from pathlib import Path


DEFAULT_BASE = "https://kf.miaola.work/"
ROOT = Path(__file__).resolve().parents[1]


class Page(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.forms = []
        self.links = []
        self.refreshes = []
        self.text = []
        self._form = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form":
            self._form = {"action": attrs.get("action", ""),
                          "method": attrs.get("method", "get").upper(), "fields": {}}
            self.forms.append(self._form)
        elif tag == "input" and self._form is not None:
            name = attrs.get("name")
            if name:
                self._form["fields"][name] = attrs.get("value", "")
        elif tag == "a" and "href" in attrs:
            self.links.append(attrs["href"])
        elif tag == "meta" and attrs.get("http-equiv", "").lower() == "refresh":
            match = re.search(r"url\s*=\s*['\"]?([^'\"]+)", attrs.get("content", ""), re.I)
            if match:
                self.refreshes.append(match[1].strip())

    def handle_endtag(self, tag):
        if tag == "form":
            self._form = None

    def handle_data(self, data):
        self.text.append(data)


def origin(url):
    parts = urllib.parse.urlsplit(url)
    return parts.scheme, parts.hostname, parts.port or 443


def same_origin(base, target):
    url = urllib.parse.urljoin(base, target)
    if origin(base) != origin(url):
        raise ValueError("Cross-origin request blocked")
    return url


def safe_url(url):
    parts = urllib.parse.urlsplit(url)
    # Deliberately omit query values: they may contain credentials or tokens.
    keys = urllib.parse.parse_qs(parts.query, keep_blank_values=True)
    query = "&".join(urllib.parse.quote(k) + "=<redacted>" for k in keys)
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path, query, ""))


class SameOriginRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self, base):
        self.base = base

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        same_origin(self.base, newurl)
        if req.data is not None and code in (307, 308):
            raise ValueError("Refusing automatic credential POST replay")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def decode_html(raw, headers):
    charset = headers.get_content_charset()
    if not charset:
        match = re.search(br'charset\s*=\s*["\x27]?([a-zA-Z0-9_-]+)', raw[:4096])
        charset = match[1].decode("ascii") if match else "gb18030"
    if charset.lower() in ("gbk", "gb2312"):
        charset = "gb18030"
    return raw.decode(charset), charset


def encode_form(fields):
    return urllib.parse.urlencode(fields, encoding="gbk", errors="strict").encode("ascii")


def authenticated(page):
    return any(urllib.parse.urlsplit(link).path.endswith("login.php")
               and urllib.parse.parse_qs(urllib.parse.urlsplit(link).query).get("action") == ["quit"]
               for link in page.links)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=DEFAULT_BASE)
    args = parser.parse_args()
    base = args.base_url.rstrip("/") + "/"
    parts = urllib.parse.urlsplit(base)
    if parts.scheme != "https" or not parts.hostname or parts.username or parts.password or parts.query or parts.fragment or parts.path != "/":
        raise ValueError("Base URL must be an HTTPS origin without credentials")

    username = os.environ.pop("KF_USERNAME", None) or input("Username: ")
    password = os.environ.pop("KF_PASSWORD", None) or getpass.getpass("Password: ")
    jar = http.cookiejar.MozillaCookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), SameOriginRedirect(base))
    opener.addheaders = [("User-Agent", "KF-Mobile-LoginProbe/0.1"),
                         ("Accept", "text/html,application/xhtml+xml")]
    report = {"base_url": base, "requests": []}

    def request(path, data=None):
        url = same_origin(base, path)
        headers = {"Referer": urllib.parse.urljoin(base, "login.php")}
        if data is not None:
            headers.update({"Content-Type": "application/x-www-form-urlencoded", "Origin": base.rstrip("/")})
        req = urllib.request.Request(url, data=data, headers=headers)
        with opener.open(req, timeout=30) as response:
            text, charset = decode_html(response.read(), response.headers)
            page = Page()
            page.feed(text)
            cookie_names = sorted({c.name for c in jar})
            report["requests"].append({"method": "POST" if data is not None else "GET",
                                       "requested_url": safe_url(url), "final_url": safe_url(response.url),
                                       "status": response.status, "charset": charset,
                                       "cookie_names": cookie_names,
                                       "authenticated_marker": authenticated(page),
                                       "meta_refresh": [safe_url(urllib.parse.urljoin(base, u)) for u in page.refreshes]})
            return page, text

    login, _ = request("login.php")
    form = next((f for f in login.forms if "pwuser" in f["fields"] and "pwpwd" in f["fields"]), None)
    if not form or form["method"] != "POST":
        raise ValueError("Expected login form was not found; no credentials submitted")
    report["login_form"] = {"method": form["method"], "action": safe_url(same_origin(base, form["action"])),
                            "field_names": list(form["fields"]),
                            "hidden_defaults": {k: v for k, v in form["fields"].items()
                                                if k in ("step", "lgt", "hideid", "cktime")}}
    fields = dict(form["fields"])
    fields.update(pwuser=username, pwpwd=password)
    result, text = request(form["action"], encode_form(fields))
    password = None
    fields.clear()
    # HTML/JS navigation is not automatically executed. A safe GET to the home
    # page independently verifies server-side authentication instead.
    report["login_result"] = {
        "success_text": "您已经顺利登录" in text or "登录成功" in text,
        "mentions_password_error": "密码错误" in text or "密码不正确" in text,
        "mentions_captcha": "验证码" in text,
        "js_navigation_present": bool(re.search(r'(?:location(?:\.href)?\s*=|location\.replace\s*\()', text)),
    }
    home, _ = request("index.php")
    report["authenticated"] = authenticated(home)
    if report["authenticated"]:
        # Read only. Do not follow links or visit state-changing endpoints.
        report["home_routes"] = sorted({urllib.parse.urlsplit(link).path for link in home.links
                                         if not urllib.parse.urlsplit(link).scheme
                                         and not link.startswith(("#", "//"))})
        secret_dir = ROOT / ".secrets"
        secret_dir.mkdir(exist_ok=True)
        # Domain-specific file: never implicitly share cookies between hosts.
        cookie_path = secret_dir / (parts.hostname + ".cookies.txt")
        jar.save(str(cookie_path), ignore_discard=True, ignore_expires=False)
        try:
            cookie_path.chmod(0o600)
        except OSError:
            pass
        report["cookie_jar"] = ".secrets/" + cookie_path.name
    output = ROOT / "docs" / "login-probe-result.json"
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True, indent=2))
    return 0 if report["authenticated"] else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError) as error:
        # Do not dump request objects, bodies, headers, or credentials.
        print("Probe failed:", type(error).__name__)
        raise SystemExit(1)
