import copy
import json
from http.server import ThreadingHTTPServer
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import server


VALID_CONFIG = {
    "school_timezone": "America/Phoenix",
    "school_dates": {"2026-10-19": 1, "2026-10-20": "no_school"},
    "children": [
        {"name": "A", "days": {"1": {"activity": "PE", "sneakers": True}}}
    ],
    "events": [{"date": "2026-10-22", "title": "Meeting"}],
    "custom_root_field": {"kept": True},
}


class AdminHttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.config_path = Path(self.temp.name) / "config.json"
        self.original_bytes = json.dumps(VALID_CONFIG, indent=2).encode("utf-8")
        self.config_path.write_bytes(self.original_bytes)
        self.httpd = ThreadingHTTPServer(
            ("127.0.0.1", 0), server.make_handler(self.config_path, server.WeatherCache())
        )
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.httpd.server_port}"

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, path, *, method="GET", body=None, headers=None):
        request = Request(self.base + path, data=body, method=method, headers=headers or {})
        try:
            with urlopen(request) as response:
                return response.status, response.headers, response.read()
        except HTTPError as error:
            result = (error.code, error.headers, error.read())
            error.close()
            return result

    def get_admin_config(self):
        status, headers, body = self.request("/api/config")
        self.assertEqual(status, 200, body.decode(errors="replace"))
        self.assertEqual(headers.get("Cache-Control"), "no-store")
        return json.loads(body)

    def post_config(self, config, revision, token, *, content_type="application/json",
                    origin=None, csrf=None, body_override=None, extra_headers=None):
        body = body_override
        if body is None:
            body = json.dumps({"config": config, "revision": revision}).encode("utf-8")
        headers = {
            "Content-Type": content_type,
            "Origin": self.base if origin is None else origin,
            "X-CSRF-Token": token if csrf is None else csrf,
        }
        headers.update(extra_headers or {})
        return self.request("/api/config", method="POST", body=body, headers=headers)

    def test_admin_page_and_config_endpoint_supply_editing_state(self):
        status, headers, body = self.request("/admin")
        self.assertEqual(status, 200)
        self.assertIn("text/html", headers.get("Content-Type", ""))
        self.assertIn("no-store", headers.get("Cache-Control", ""))
        self.assertTrue(body)

        result = self.get_admin_config()
        self.assertEqual(set(result), {"config", "revision", "csrf_token"})
        self.assertEqual(result["config"], VALID_CONFIG)
        self.assertTrue(result["revision"])
        self.assertTrue(result["csrf_token"])

    def test_save_persists_config_and_timestamped_backup_of_previous_bytes(self):
        state = self.get_admin_config()
        changed = copy.deepcopy(VALID_CONFIG)
        changed["events"][0]["title"] = "Updated"

        status, headers, body = self.post_config(changed, state["revision"], state["csrf_token"])
        self.assertEqual(status, 200, body.decode(errors="replace"))
        self.assertEqual(headers.get("Cache-Control"), "no-store")
        saved = json.loads(self.config_path.read_text(encoding="utf-8"))
        self.assertEqual(saved, changed)
        backups = list((self.config_path.parent / "backups").glob("*"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_bytes(), self.original_bytes)

        updated_state = self.get_admin_config()
        self.assertEqual(updated_state["config"], changed)
        self.assertNotEqual(updated_state["revision"], state["revision"])

    def test_stale_revision_conflicts_after_an_external_file_edit(self):
        state = self.get_admin_config()
        external = copy.deepcopy(VALID_CONFIG)
        external["events"][0]["title"] = "External edit"
        external_bytes = json.dumps(external).encode("utf-8")
        self.config_path.write_bytes(external_bytes)

        proposed = copy.deepcopy(VALID_CONFIG)
        proposed["events"][0]["title"] = "My edit"
        status, _, _ = self.post_config(proposed, state["revision"], state["csrf_token"])
        self.assertEqual(status, 409)
        self.assertEqual(self.config_path.read_bytes(), external_bytes)
        backups_dir = self.config_path.parent / "backups"
        self.assertFalse(backups_dir.exists() and list(backups_dir.iterdir()))

    def test_rejects_missing_or_wrong_origin_and_csrf_without_mutation(self):
        state = self.get_admin_config()
        cases = [
            {"origin": ""},
            {"origin": "http://elsewhere.invalid"},
            {"csrf": ""},
            {"csrf": "incorrect-token"},
        ]
        for kwargs in cases:
            with self.subTest(kwargs=kwargs):
                status, _, _ = self.post_config(VALID_CONFIG, state["revision"], state["csrf_token"], **kwargs)
                self.assertEqual(status, 403)
                self.assertEqual(self.config_path.read_bytes(), self.original_bytes)

    def test_rejects_wrong_content_type_bad_json_invalid_config_and_large_body(self):
        state = self.get_admin_config()
        prefix = {"config": VALID_CONFIG, "revision": state["revision"]}
        invalid_configs = []
        for mutate in (
            lambda c: c.update(school_timezone="Mars/Olympus"),
            lambda c: c.update(school_dates={"2026-2-03": 1}),
            lambda c: c.update(school_dates={"2026-10-19": True}),
            lambda c: c.update(children=[{"name": "A", "days": {"1": {"activity": 4, "sneakers": True}}}]),
            lambda c: c.update(children=[{"name": "A", "days": {"1": {"activity": "PE", "sneakers": 1}}}]),
            lambda c: c.update(events=[{"date": "2026-10-22", "title": ""}]),
            lambda c: c.update(weather={"latitude": 91, "longitude": 0, "location": "City"}),
            lambda c: c.update(weather={"latitude": float("nan"), "longitude": 0, "location": "City"}),
        ):
            invalid = copy.deepcopy(VALID_CONFIG)
            mutate(invalid)
            invalid_configs.append(invalid)
        cases = [
            ("text/plain", json.dumps(prefix).encode(), 415),
            ("application/json", b"{broken", 400),
            ("application/json", b" " * (256 * 1024 + 1), 413),
        ]
        cases.extend(("application/json", json.dumps({"config": invalid, "revision": state["revision"]}).encode(), 400)
                     for invalid in invalid_configs)
        for content_type, body, expected in cases:
            with self.subTest(content_type=content_type, expected=expected):
                status, _, _ = self.post_config(VALID_CONFIG, state["revision"], state["csrf_token"],
                                                content_type=content_type, body_override=body)
                self.assertEqual(status, expected)
                self.assertEqual(self.config_path.read_bytes(), self.original_bytes)

    def test_missing_or_malformed_saved_config_returns_500_without_repairing_file(self):
        original_bytes = self.config_path.read_bytes()
        self.config_path.unlink()
        status, _, _ = self.request("/api/config")
        self.assertEqual(status, 500)
        self.assertFalse(self.config_path.exists())

        malformed = b"{not json"
        self.config_path.write_bytes(malformed)
        status, _, _ = self.request("/api/config")
        self.assertEqual(status, 500)
        self.assertEqual(self.config_path.read_bytes(), malformed)
        self.assertNotEqual(original_bytes, malformed)


if __name__ == "__main__":
    unittest.main()
