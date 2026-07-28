from __future__ import annotations

import json
import unittest
from datetime import datetime, timezone
from types import SimpleNamespace

import photobook_bridge


class _PostWithoutLocation(SimpleNamespace):
    @property
    def location(self):
        raise KeyError("location")


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


if __name__ == "__main__":
    unittest.main()
