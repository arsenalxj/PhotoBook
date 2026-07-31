from __future__ import annotations

import json
import unittest
from unittest.mock import Mock, patch

import xiaohongshu_bridge


class XiaohongshuBridgeTest(unittest.TestCase):
    def test_upgrades_plain_http_urls_only_for_official_media_cdns(self) -> None:
        note = {
            "noteId": "64abc123456789ab",
            "user": {
                "userId": "author-1",
                "avatar": "http://sns-avatar-qc.xhscdn.com/avatar.jpg?imageView2=2",
            },
            "imageList": [
                {
                    "url": "http://sns-webpic-qc.xhscdn.com/still.jpg?imageView2=2",
                    "livePhoto": "http://sns-video-qc.xhscdn.com/motion.mp4",
                },
                {"url": "http://example.com/untrusted.jpg"},
                {"url": "http://user@sns-webpic-qc.xhscdn.com/credential.jpg"},
                {"url": "http://sns-webpic-qc.xhscdn.com:8080/port.jpg"},
            ],
        }

        payload = xiaohongshu_bridge._note_payload(
            note,
            "https://www.xiaohongshu.com/explore/64abc123456789ab",
        )

        self.assertEqual(
            payload["authorAvatarUrl"],
            "https://sns-avatar-qc.xhscdn.com/avatar.jpg?imageView2=2",
        )
        self.assertEqual(
            [item["url"] for item in payload["media"]],
            [
                "https://sns-webpic-qc.xhscdn.com/still.jpg?imageView2=2",
                "https://sns-video-qc.xhscdn.com/motion.mp4",
                "http://example.com/untrusted.jpg",
                "http://user@sns-webpic-qc.xhscdn.com/credential.jpg",
                "http://sns-webpic-qc.xhscdn.com:8080/port.jpg",
            ],
        )

    def test_video_note_uses_video_instead_of_image_list_cover(self) -> None:
        note = {
            "noteId": "64abc123456789ab",
            "type": "video",
            "user": {"userId": "author-1"},
            "imageList": [
                {
                    "width": 1080,
                    "height": 1440,
                    "url": "https://ci.example/video-cover.jpg",
                }
            ],
            "video": {
                "width": 1080,
                "height": 1920,
                "media": {
                    "stream": {
                        "h264": [
                            {
                                "masterUrl": "https://sns-video.example/video.mp4",
                            }
                        ]
                    }
                },
            },
        }

        payload = xiaohongshu_bridge._note_payload(
            note,
            "https://www.xiaohongshu.com/explore/64abc123456789ab",
        )

        self.assertEqual(len(payload["media"]), 1)
        self.assertEqual(payload["media"][0]["mediaType"], "video")
        self.assertEqual(payload["media"][0]["mediaRole"], "primary")
        self.assertEqual(
            payload["media"][0]["url"],
            "https://sns-video.example/video.mp4",
        )

    def test_prefers_detail_image_over_trailing_preview(self) -> None:
        image = {
            "url": "https://ci.example/detail-fallback.jpg",
            "infoList": [
                {
                    "imageScene": "H5_DTL",
                    "url": "https://ci.example/detail.jpg",
                },
                {
                    "imageScene": "H5_PRV",
                    "url": "https://ci.example/preview.jpg",
                },
            ],
        }

        self.assertEqual(
            xiaohongshu_bridge._image_url(image),
            "https://ci.example/detail.jpg",
        )

    def test_prefers_unwatermarked_original_jpeg_and_keeps_h5_fallback(self) -> None:
        image = {
            "fileId": "notes_uhdr/1040g3qo-original",
            "infoList": [
                {
                    "imageScene": "H5_DTL",
                    "url": (
                        "http://sns-webpic-qc.xhscdn.com/timestamp/signature/"
                        "notes_uhdr/1040g3qo-original!h5_1080jpg"
                    ),
                },
                {
                    "imageScene": "H5_PRV",
                    "url": "http://sns-webpic-qc.xhscdn.com/preview!style",
                },
            ],
        }

        primary, fallback = xiaohongshu_bridge._image_urls(image)

        self.assertEqual(
            primary,
            (
                "https://sns-img-qc.xhscdn.com/notes_uhdr/1040g3qo-original"
                "?imageView2/2/format/jpg"
            ),
        )
        self.assertEqual(
            fallback,
            (
                "http://sns-webpic-qc.xhscdn.com/timestamp/signature/"
                "notes_uhdr/1040g3qo-original!h5_1080jpg"
            ),
        )

    def test_media_payload_includes_fallback_only_for_original_image(self) -> None:
        image = {
            "fileId": "1040g2-original",
            "infoList": [
                {
                    "imageScene": "H5_DTL",
                    "url": "http://sns-webpic-hw.xhscdn.com/detail!h5_1080jpg",
                }
            ],
        }

        payload = xiaohongshu_bridge._note_payload(
            {
                "noteId": "64abc123456789ab",
                "user": {"userId": "author-1"},
                "imageList": [image, {"url": "https://sns-webpic-hw.xhscdn.com/plain.jpg"}],
            },
            "https://www.xiaohongshu.com/explore/64abc123456789ab",
        )

        self.assertEqual(
            payload["media"][0]["fallbackUrl"],
            "https://sns-webpic-hw.xhscdn.com/detail!h5_1080jpg",
        )
        self.assertNotIn("fallbackUrl", payload["media"][1])

    def test_video_note_does_not_fall_back_to_cover_when_video_is_missing(self) -> None:
        note = {
            "noteId": "64abc123456789ab",
            "type": "video",
            "user": {"userId": "author-1"},
            "imageList": [{"url": "https://ci.example/video-cover.jpg"}],
        }

        with self.assertRaises(RuntimeError) as caught:
            xiaohongshu_bridge._note_payload(
                note,
                "https://www.xiaohongshu.com/explore/64abc123456789ab",
            )

        self.assertEqual(json.loads(str(caught.exception))["code"], "INVALID_RESPONSE")

    def test_parses_live_photo_and_strips_request_token_from_canonical_url(self) -> None:
        state = {
            "note": {
                "noteDetailMap": {
                    "64abc123456789ab": {
                        "note": {
                            "noteId": "64abc123456789ab",
                            "desc": "正文",
                            "time": 1_750_000_000_000,
                            "user": {
                                "userId": "author-1",
                                "nickname": "作者",
                                "avatar": "https://sns-avatar.example/avatar.jpg",
                            },
                            "imageList": [
                                {
                                    "width": 1080,
                                    "height": 1440,
                                    "infoList": [
                                        {
                                            "imageScene": "WB_DFT",
                                            "url": "https://ci.example/still.jpg",
                                        }
                                    ],
                                    "livePhoto": {
                                        "media": {
                                            "stream": {
                                                "h264": [
                                                    {
                                                        "masterUrl": "https://sns-video.example/motion.mp4"
                                                    }
                                                ]
                                            }
                                        }
                                    },
                                }
                            ],
                        }
                    }
                }
            },
            "unused": None,
        }
        response = Mock(status_code=200, text=f"<script>window.__INITIAL_STATE__={json.dumps(state)};</script>")
        with patch.object(xiaohongshu_bridge.requests.Session, "get", return_value=response):
            payload = json.loads(
                xiaohongshu_bridge.fetch_post(
                    "https://www.xiaohongshu.com/explore/64abc123456789ab?xsec_token=secret"
                )
            )

        self.assertEqual(payload["sourcePlatform"], "xiaohongshu")
        self.assertEqual(
            payload["sourceUrl"],
            "https://www.xiaohongshu.com/explore/64abc123456789ab",
        )
        self.assertEqual(payload["authorUsername"], "author-1")
        self.assertEqual(payload["authorDisplayName"], "作者")
        self.assertNotIn("secret", json.dumps(payload))
        self.assertEqual(
            [item["mediaRole"] for item in payload["media"]],
            ["live_still", "live_motion"],
        )
        self.assertEqual([item["logicalIndex"] for item in payload["media"]], [0, 0])

    def test_rejects_redirect_to_untrusted_host(self) -> None:
        response = Mock(status_code=302, headers={"Location": "https://example.com/private"})
        with patch.object(xiaohongshu_bridge.requests.Session, "get", return_value=response):
            with self.assertRaises(RuntimeError) as caught:
                xiaohongshu_bridge.fetch_post("https://xhslink.com/a/test")
        self.assertEqual(json.loads(str(caught.exception))["code"], "INVALID_URL")

    def test_selects_note_matching_final_url(self) -> None:
        state = {
            "note": {
                "noteDetailMap": {
                    "unrelated1234567": {
                        "note": {
                            "noteId": "unrelated1234567",
                            "user": {"userId": "wrong"},
                            "imageList": [{"url": "https://ci.example/wrong.jpg"}],
                        }
                    },
                    "64abc123456789ab": {
                        "note": {
                            "noteId": "64abc123456789ab",
                            "user": {"userId": "right"},
                            "imageList": [{"url": "https://ci.example/right.jpg"}],
                        }
                    },
                }
            }
        }
        response = Mock(
            status_code=200,
            text=f"<script>window.__INITIAL_STATE__={json.dumps(state)};</script>",
        )
        with patch.object(
            xiaohongshu_bridge.requests.Session, "get", return_value=response
        ):
            payload = json.loads(
                xiaohongshu_bridge.fetch_post(
                    "https://www.xiaohongshu.com/explore/64abc123456789ab"
                )
            )

        self.assertEqual(payload["sourcePostId"], "64abc123456789ab")
        self.assertEqual(payload["authorUsername"], "right")
        self.assertEqual(payload["authorDisplayName"], "right")

    def test_balanced_state_parser_handles_undefined(self) -> None:
        state = xiaohongshu_bridge._parse_initial_state(
            '<script>window.__INITIAL_STATE__={"note":{"value":undefined}};</script>'
        )
        self.assertIsNone(state["note"]["value"])


if __name__ == "__main__":
    unittest.main()
