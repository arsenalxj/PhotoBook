from __future__ import annotations

import json
import unittest
from datetime import datetime, timezone
from types import SimpleNamespace
from unittest.mock import patch

import photobook_bridge


class _PostWithoutLocation(SimpleNamespace):
    @property
    def location(self):
        raise KeyError("location")


class _FakeContext:
    def __init__(self) -> None:
        self.username = None
        self.cookies: dict[str, str] = {}

    def update_cookies(self, cookies: dict[str, str]) -> None:
        self.cookies.update(cookies)


class _FakeLoader:
    def __init__(self, username: str | None = "archive_user") -> None:
        self.context = _FakeContext()
        self.login_username = username
        self.loaded_session: tuple[str, dict[str, str]] | None = None

    def test_login(self) -> str | None:
        return self.login_username

    def load_session(self, username: str, cookies: dict[str, str]) -> None:
        self.context.username = username
        self.context.cookies = dict(cookies)
        self.loaded_session = (username, dict(cookies))

    def save_session(self) -> dict[str, str]:
        return {
            **self.context.cookies,
            "sessionid": "refreshed-session",
            "csrftoken": "refreshed-csrf",
        }


class BridgePayloadTest(unittest.TestCase):
    def test_health_check_reports_pinned_runtime(self) -> None:
        payload = json.loads(photobook_bridge.health_check())

        self.assertTrue(payload["ok"])
        self.assertTrue(payload["instaloaderVersion"])
        self.assertEqual(
            payload["forkCommit"],
            "b1d233362e335cbbccba5c5e4b614a1032764118",
        )

    def test_serializes_sidecar_without_downloading_media(self) -> None:
        profile = SimpleNamespace(
            username="author",
            full_name="Author",
            profile_pic_url="https://cdn.example/avatar.jpg",
        )
        image = SimpleNamespace(
            is_video=False,
            display_url="https://cdn.example/image.jpg",
            _node={"dimensions": {"width": 1080, "height": 1350}},
        )
        video = SimpleNamespace(
            is_video=True,
            video_url="https://cdn.example/video.mp4",
            _node={
                "dimensions": {"width": 1080, "height": 1920},
                "video_duration": 3.25,
            },
        )
        post = SimpleNamespace(
            shortcode="Abc_123",
            owner_profile=profile,
            typename="GraphSidecar",
            caption="正文",
            date_utc=datetime(2025, 1, 2, tzinfo=timezone.utc),
            location={"name": "Shanghai"},
            _node={},
            get_sidecar_nodes=lambda: [image, video],
        )

        payload = photobook_bridge._post_payload(post, expected_shortcode="Abc_123")

        self.assertEqual(payload["sourcePostId"], "Abc_123")
        self.assertEqual(payload["authorUsername"], "author")
        self.assertEqual(payload["locationName"], "Shanghai")
        self.assertEqual([item["mediaType"] for item in payload["media"]], ["image", "video"])
        self.assertEqual(payload["media"][1]["durationMs"], 3250)

    def test_rejects_mismatched_shortcode(self) -> None:
        post = SimpleNamespace(shortcode="Other")
        with self.assertRaises(RuntimeError) as caught:
            photobook_bridge._post_payload(post, expected_shortcode="Expected")
        error = json.loads(str(caught.exception))
        self.assertEqual(error["code"], "SOURCE_MISMATCH")

    def test_missing_optional_location_does_not_abort_payload(self) -> None:
        post = _PostWithoutLocation(
            shortcode="NoLocation",
            owner_profile=SimpleNamespace(
                username="author",
                full_name="Author",
                profile_pic_url=None,
            ),
            typename="GraphImage",
            caption=None,
            date_utc=datetime(2025, 1, 2, tzinfo=timezone.utc),
            is_video=False,
            display_url="https://cdn.example/image.jpg",
            _node={"dimensions": {"width": 1080, "height": 1350}},
        )

        payload = photobook_bridge._post_payload(post, expected_shortcode="NoLocation")

        self.assertIsNone(payload["locationName"])


class SessionBridgeTest(unittest.TestCase):
    def test_parses_complete_cookie_header_without_exposing_secret(self) -> None:
        cookies = photobook_bridge._cookies_from_header(
            "sessionid=session-secret; csrftoken=csrf-value; ds_user_id=123; rur=ASH"
        )

        self.assertEqual(cookies["sessionid"], "session-secret")
        self.assertEqual(cookies["csrftoken"], "csrf-value")
        self.assertEqual(cookies["rur"], "ASH")

        with self.assertRaises(RuntimeError) as caught:
            photobook_bridge._cookies_from_header(
                "sessionid=must-not-leak; ds_user_id=123"
            )
        error = json.loads(str(caught.exception))
        self.assertEqual(error["code"], "LOGIN_INCOMPLETE")
        self.assertNotIn("must-not-leak", str(caught.exception))

    def test_validates_cookie_and_returns_normalized_session(self) -> None:
        loader = _FakeLoader()
        with patch.object(photobook_bridge.instaloader, "Instaloader", return_value=loader):
            payload = json.loads(
                photobook_bridge.validate_session(
                    "sessionid=session-value; csrftoken=csrf-value; mid=device-value"
                )
            )

        self.assertEqual(payload["username"], "archive_user")
        self.assertEqual(payload["cookies"]["sessionid"], "refreshed-session")
        self.assertEqual(loader.context.cookies["mid"], "device-value")

    def test_validation_failure_returns_stable_error(self) -> None:
        loader = _FakeLoader(username=None)
        with patch.object(photobook_bridge.instaloader, "Instaloader", return_value=loader):
            with self.assertRaises(RuntimeError) as caught:
                photobook_bridge.validate_session(
                    "sessionid=session-value; csrftoken=csrf-value"
                )

        error = json.loads(str(caught.exception))
        self.assertEqual(error["code"], "LOGIN_VALIDATION_FAILED")

    def test_fetch_returns_no_session_for_anonymous_request(self) -> None:
        loader = _FakeLoader()
        post = _post(is_private=False)
        with (
            patch.object(photobook_bridge.instaloader, "Instaloader", return_value=loader),
            patch.object(
                photobook_bridge.instaloader.Post,
                "from_shortcode",
                return_value=post,
            ),
        ):
            payload = json.loads(photobook_bridge.fetch_post("PublicPost"))

        self.assertEqual(payload["post"]["sourcePostId"], "PublicPost")
        self.assertIsNone(payload["refreshedSession"])
        self.assertIsNone(loader.loaded_session)

    def test_authenticated_fetch_loads_and_refreshes_session(self) -> None:
        loader = _FakeLoader()
        post = _post(is_private=False)
        session = json.dumps(
            {
                "username": "archive_user",
                "cookies": {
                    "sessionid": "old-session",
                    "csrftoken": "old-csrf",
                },
            }
        )
        with (
            patch.object(photobook_bridge.instaloader, "Instaloader", return_value=loader),
            patch.object(
                photobook_bridge.instaloader.Post,
                "from_shortcode",
                return_value=post,
            ),
        ):
            payload = json.loads(photobook_bridge.fetch_post("PublicPost", session))

        self.assertEqual(loader.loaded_session[0], "archive_user")
        self.assertEqual(payload["refreshedSession"]["cookies"]["sessionid"], "refreshed-session")

    def test_rejects_private_profile_even_with_session(self) -> None:
        loader = _FakeLoader()
        session = json.dumps(
            {
                "username": "archive_user",
                "cookies": {
                    "sessionid": "session-value",
                    "csrftoken": "csrf-value",
                },
            }
        )
        with (
            patch.object(photobook_bridge.instaloader, "Instaloader", return_value=loader),
            patch.object(
                photobook_bridge.instaloader.Post,
                "from_shortcode",
                return_value=_post(is_private=True),
            ),
        ):
            with self.assertRaises(RuntimeError) as caught:
                photobook_bridge.fetch_post("PrivatePost", session)

        error = json.loads(str(caught.exception))
        self.assertEqual(error["code"], "POST_UNAVAILABLE")


def _post(*, is_private: bool) -> SimpleNamespace:
    profile = SimpleNamespace(
        username="author",
        full_name="Author",
        profile_pic_url=None,
        is_private=is_private,
    )
    return SimpleNamespace(
        shortcode="PublicPost" if not is_private else "PrivatePost",
        owner_profile=profile,
        typename="GraphImage",
        caption=None,
        date_utc=datetime(2025, 1, 2, tzinfo=timezone.utc),
        location=None,
        is_video=False,
        display_url="https://cdn.example/image.jpg",
        _node={"dimensions": {"width": 1080, "height": 1350}},
    )


if __name__ == "__main__":
    unittest.main()
