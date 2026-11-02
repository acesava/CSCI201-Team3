import contextlib
import http.client
import io
import sys
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import supabase_health


TEST_KEY = "sb_publishable_health_test"


class Response(io.BytesIO):
    def __init__(self, body, status=200):
        super().__init__(body)
        self.status = status


class SupabaseHealthTests(unittest.TestCase):
    def test_success_makes_one_direct_read_only_request(self):
        opener = Mock()
        opener.open.return_value = Response(b"1")
        supabase_health.check_database(TEST_KEY, opener)
        opener.open.assert_called_once()
        args, kwargs = opener.open.call_args
        request = args[0]
        self.assertEqual(request.full_url,
                         "https://klxskdrnvslhqrwmuiuu.supabase.co/rest/v1/rpc/check_database_health")
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(request.data, b"{}")
        self.assertEqual(request.get_header("Apikey"), TEST_KEY)
        self.assertIsNone(request.get_header("Authorization"))
        self.assertEqual(kwargs["timeout"], 15)

    def test_missing_elevated_legacy_or_malformed_keys_never_send_request(self):
        for key in (None, "", "sb_secret_test", "eyJhbGciOiJIUzI1NiJ9.legacy.key",
                    TEST_KEY + "\n", "sb_publishable_", "sb_publishable_bad key"):
            with self.subTest(key=key):
                opener = Mock()
                with self.assertRaises(supabase_health.HealthCheckError):
                    supabase_health.check_database(key, opener)
                opener.open.assert_not_called()

    def test_bool_float_string_and_other_json_are_not_database_success(self):
        for body in (b"true", b"1.0", b'"1"', b"0", b"null", b"[]", b'{"value":1}'):
            with self.subTest(body=body):
                opener = Mock()
                opener.open.return_value = Response(body)
                with self.assertRaisesRegex(supabase_health.HealthCheckError,
                                            "unexpected database response"):
                    supabase_health.check_database(TEST_KEY, opener)

    def test_invalid_and_oversized_responses_fail_without_logging_bodies(self):
        for body in (b"", b"<html>Unavailable</html>", b"\xff", b"1" + b" " * 1000):
            with self.subTest(body_length=len(body)):
                opener = Mock()
                opener.open.return_value = Response(body)
                with self.assertRaises(supabase_health.HealthCheckError) as raised:
                    supabase_health.check_database(TEST_KEY, opener)
                self.assertNotIn("Unavailable", str(raised.exception))

    def test_http_and_network_failures_are_sanitized_and_never_retried(self):
        failures = [
            urllib.error.HTTPError(supabase_health.HEALTH_URL, 401, TEST_KEY, {}, None),
            urllib.error.HTTPError(supabase_health.HEALTH_URL, 429, TEST_KEY, {}, None),
            urllib.error.URLError(TEST_KEY),
            TimeoutError(TEST_KEY),
            http.client.IncompleteRead(TEST_KEY.encode()),
        ]
        for failure in failures:
            with self.subTest(failure=type(failure).__name__):
                opener = Mock()
                opener.open.side_effect = failure
                with self.assertRaises(supabase_health.HealthCheckError) as raised:
                    supabase_health.check_database(TEST_KEY, opener)
                self.assertNotIn(TEST_KEY, str(raised.exception))
                opener.open.assert_called_once()

    def test_non_200_response_is_not_success(self):
        opener = Mock()
        opener.open.return_value = Response(b"1", status=204)
        with self.assertRaisesRegex(supabase_health.HealthCheckError, "HTTP status"):
            supabase_health.check_database(TEST_KEY, opener)

    def test_actual_http_redirects_never_reach_destination(self):
        paths = []

        class RedirectServer(BaseHTTPRequestHandler):
            def do_POST(self):
                paths.append(self.path)
                self.send_response(int(self.path[1:]))
                self.send_header("Location", "/destination")
                self.end_headers()

            def do_GET(self):
                paths.append(self.path)
                self.send_response(200)
                self.end_headers()

            def log_message(self, format, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), RedirectServer)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            opener = urllib.request.build_opener(supabase_health.NoRedirectHandler())
            for status in (301, 302, 303, 307, 308):
                with self.subTest(status=status):
                    request = urllib.request.Request(
                        f"http://127.0.0.1:{server.server_port}/{status}",
                        data=b"{}", headers={"apikey": TEST_KEY}, method="POST")
                    with self.assertRaises((supabase_health.HealthCheckError,
                                            urllib.error.HTTPError)):
                        opener.open(request, timeout=2)
            self.assertEqual(paths, ["/301", "/302", "/303", "/307", "/308"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_cli_reports_failure_without_credentials(self):
        stderr = io.StringIO()
        with patch.dict("os.environ", {"SUPABASE_HEALTH_KEY": "sb_secret_private"}), \
                patch.object(sys, "argv", ["supabase_health.py"]), \
                contextlib.redirect_stderr(stderr):
            result = supabase_health.main()
        self.assertEqual(result, 1)
        self.assertIn("must be a publishable key", stderr.getvalue())
        self.assertNotIn("sb_secret_private", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
