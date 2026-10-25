import unittest
from probe_login import Page, authenticated, encode_form, same_origin, safe_url


class LoginProbeTests(unittest.TestCase):
    def test_gbk_form_encoding(self):
        self.assertEqual(encode_form({"pwuser": "测试用户", "pwpwd": "a!+&"}),
                         b"pwuser=%B2%E2%CA%D4%D3%C3%BB%A7&pwpwd=a%21%2B%26")

    def test_cross_origin_blocked(self):
        for target in ["https://example.com/", "//example.com/", "http://kf.miaola.work/", "https://1.kf.miaola.work/"]:
            with self.subTest(target=target), self.assertRaises(ValueError):
                same_origin("https://kf.miaola.work/", target)

    def test_relative_url(self):
        self.assertEqual(same_origin("https://kf.miaola.work/", "login.php?"),
                         "https://kf.miaola.work/login.php")

    def test_query_redaction(self):
        self.assertEqual(safe_url("https://example.com/login.php?token=secret#secret"),
                         "https://example.com/login.php?token=<redacted>")

    def test_login_form(self):
        page = Page()
        page.feed('<form action="login.php?" method="post"><input name="step" value="2">'
                  '<input name="pwuser"><input name="pwpwd" type="password"></form>')
        self.assertEqual(page.forms[0]["fields"], {"step": "2", "pwuser": "", "pwpwd": ""})
        self.assertFalse(authenticated(page))

    def test_session_marker(self):
        page = Page()
        page.feed('<a href="login.php?action=quit&amp;verify=hidden">Exit</a>')
        self.assertTrue(authenticated(page))


if __name__ == "__main__":
    unittest.main()
