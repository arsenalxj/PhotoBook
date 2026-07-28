from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from typing import Any

import instaloader


_SHORTCODE_PATTERN = re.compile(r"^[A-Za-z0-9_-]+$")
_FORK_COMMIT = "b1d233362e335cbbccba5c5e4b614a1032764118"


def health_check() -> str:
    return json.dumps(
        {
            "ok": True,
            "instaloaderVersion": instaloader.__version__,
            "forkCommit": _FORK_COMMIT,
        },
        separators=(",", ":"),
    )


def fetch_post(shortcode: str) -> str:
    normalized = str(shortcode).strip()
    if not _SHORTCODE_PATTERN.fullmatch(normalized):
        raise RuntimeError(_error_json("INVALID_URL", "Instagram 帖子编号无效"))

    try:
        loader = instaloader.Instaloader(max_connection_attempts=1)
        post = instaloader.Post.from_shortcode(loader.context, normalized)
        return json.dumps(
            _post_payload(post, expected_shortcode=normalized),
            ensure_ascii=False,
            separators=(",", ":"),
        )
    except RuntimeError:
        raise
    except Exception as error:
        code, message = _classify_error(error)
        raise RuntimeError(_error_json(code, message)) from error


def _post_payload(post: Any, *, expected_shortcode: str) -> dict[str, Any]:
    shortcode = _required_text(getattr(post, "shortcode", None), "帖子编号")
    if shortcode != expected_shortcode:
        raise RuntimeError(_error_json("SOURCE_MISMATCH", "Instagram 返回了不同的帖子编号"))

    profile = post.owner_profile
    username = _required_text(getattr(profile, "username", None), "博主用户名")
    display_name = _optional_text(getattr(profile, "full_name", None)) or username
    raw = getattr(post, "_node", None)

    if getattr(post, "typename", "") == "GraphSidecar":
        media = [
            _media_payload(node, index)
            for index, node in enumerate(post.get_sidecar_nodes())
        ]
    else:
        media = [_media_payload(post, 0)]
    if not media:
        raise RuntimeError(_error_json("INVALID_RESPONSE", "Instagram 帖子没有媒体"))

    return {
        "sourcePostId": shortcode,
        "sourceUrl": f"https://www.instagram.com/{_post_kind(post, raw)}/{shortcode}/",
        "authorUsername": username,
        "authorDisplayName": display_name,
        "authorProfileUrl": f"https://www.instagram.com/{username}/",
        "authorAvatarUrl": _optional_text(getattr(profile, "profile_pic_url", None)),
        "caption": str(getattr(post, "caption", None) or ""),
        "publishedAt": _timestamp_ms(getattr(post, "date_utc", None)),
        "locationName": _location_name(_optional_attribute(post, "location")),
        "media": media,
    }


def _media_payload(media: Any, index: int) -> dict[str, Any]:
    is_video = bool(getattr(media, "is_video", False))
    url = getattr(media, "video_url", None) if is_video else None
    if url is None:
        url = getattr(media, "display_url", None)
    if url is None:
        url = getattr(media, "url", None)

    raw = getattr(media, "_node", None)
    width, height = _dimensions(raw)
    return {
        "sortIndex": index,
        "mediaType": "video" if is_video else "image",
        "url": _required_text(url, f"第 {index + 1} 个媒体地址"),
        "width": width,
        "height": height,
        "durationMs": _duration_ms(raw) if is_video else None,
    }


def _dimensions(raw: Any) -> tuple[int | None, int | None]:
    if not isinstance(raw, dict):
        return None, None
    dimensions = raw.get("dimensions")
    if not isinstance(dimensions, dict):
        return None, None
    return _positive_int(dimensions.get("width")), _positive_int(dimensions.get("height"))


def _duration_ms(raw: Any) -> int | None:
    if not isinstance(raw, dict):
        return None
    value = raw.get("video_duration")
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value <= 0:
        return None
    return round(float(value) * 1000)


def _post_kind(post: Any, raw: Any) -> str:
    product_type = str(raw.get("product_type", "")) if isinstance(raw, dict) else ""
    if getattr(post, "typename", "") == "GraphVideo" and product_type == "clips":
        return "reel"
    return "p"


def _location_name(location: Any) -> str | None:
    if location is None:
        return None
    if isinstance(location, dict):
        return _optional_text(location.get("name"))
    return _optional_text(getattr(location, "name", None))


def _optional_attribute(value: Any, name: str) -> Any:
    try:
        return getattr(value, name, None)
    except KeyError:
        return None


def _timestamp_ms(value: Any) -> int:
    if not isinstance(value, datetime):
        raise RuntimeError(_error_json("INVALID_RESPONSE", "Instagram 未返回有效发布时间"))
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return round(value.timestamp() * 1000)


def _positive_int(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        return None
    return value


def _required_text(value: Any, field: str) -> str:
    normalized = _optional_text(value)
    if normalized is None:
        raise RuntimeError(_error_json("INVALID_RESPONSE", f"Instagram 未返回{field}"))
    return normalized


def _optional_text(value: Any) -> str | None:
    if value is None:
        return None
    normalized = str(value).strip()
    return normalized or None


def _classify_error(error: Exception) -> tuple[str, str]:
    name = type(error).__name__
    if name in {"LoginRequiredException", "PrivateProfileNotFollowedException"}:
        return "LOGIN_REQUIRED", "Instagram 要求登录，当前版本仅支持匿名访问公开帖子"
    if name in {"TooManyRequestsException"}:
        return "RATE_LIMITED", "Instagram 请求过于频繁，请稍后重试"
    if name in {"BadResponseException", "QueryReturnedNotFoundException"}:
        return "POST_UNAVAILABLE", "帖子不存在、已失效或当前不可访问"
    if name in {"ConnectionException", "QueryReturnedBadRequestException"}:
        return "NETWORK_ERROR", "连接 Instagram 失败，请检查系统网络或 VPN"
    return "INSTAGRAM_ERROR", "Instagram 帖子解析失败"


def _error_json(code: str, message: str) -> str:
    return json.dumps({"code": code, "message": message}, ensure_ascii=False, separators=(",", ":"))
