from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from http.cookies import SimpleCookie
from typing import Any

import instaloader
from instaloader import exceptions as instaloader_exceptions


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


def validate_session(cookie_header: str) -> str:
    try:
        cookies = _cookies_from_header(cookie_header)
        loader = instaloader.Instaloader(
            max_connection_attempts=1,
            request_timeout=30.0,
        )
        loader.context.update_cookies(cookies)
        username = loader.test_login()
        if not username:
            raise RuntimeError(
                _error_json(
                    "LOGIN_VALIDATION_FAILED",
                    "Instagram 登录状态无法验证，请完成页面验证后重试",
                )
            )
        loader.context.username = username
        return json.dumps(
            _session_payload(username, loader.save_session()),
            ensure_ascii=False,
            separators=(",", ":"),
        )
    except RuntimeError:
        raise
    except Exception as error:
        raise RuntimeError(
            _error_json("LOGIN_VALIDATION_FAILED", "Instagram 登录状态验证失败")
        ) from error


def fetch_post(shortcode: str, session_json: str | None = None) -> str:
    normalized = str(shortcode).strip()
    if not _SHORTCODE_PATTERN.fullmatch(normalized):
        raise RuntimeError(_error_json("INVALID_URL", "Instagram 帖子编号无效"))

    session = None
    try:
        loader = instaloader.Instaloader(
            max_connection_attempts=1,
            request_timeout=30.0,
        )
        session = _session_from_json(session_json)
        if session is not None:
            loader.load_session(session["username"], session["cookies"])
        post = instaloader.Post.from_shortcode(loader.context, normalized)
        profile = post.owner_profile
        if bool(getattr(profile, "is_private", False)):
            raise RuntimeError(_error_json("POST_UNAVAILABLE", "当前版本只支持公开帖子"))
        return json.dumps(
            {
                "post": _post_payload(post, expected_shortcode=normalized),
                "refreshedSession": (
                    _session_payload(session["username"], loader.save_session())
                    if session is not None
                    else None
                ),
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
    except RuntimeError:
        raise
    except Exception as error:
        code, message = _classify_error(error, authenticated=session is not None)
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


def _cookies_from_header(cookie_header: str) -> dict[str, str]:
    header = str(cookie_header or "").strip()
    if not header:
        raise RuntimeError(_error_json("LOGIN_INCOMPLETE", "Instagram 登录尚未完成"))
    parsed = SimpleCookie()
    try:
        parsed.load(header)
    except Exception as error:
        raise RuntimeError(_error_json("LOGIN_INCOMPLETE", "Instagram Cookie 格式无效")) from error
    return _validated_cookies({name: morsel.value for name, morsel in parsed.items()})


def _session_from_json(session_json: str | None) -> dict[str, Any] | None:
    raw = str(session_json or "").strip()
    if not raw:
        return None
    try:
        payload = json.loads(raw)
        username = str(payload["username"]).strip()
        cookies = _validated_cookies(payload["cookies"])
    except RuntimeError:
        raise
    except Exception as error:
        raise RuntimeError(_error_json("LOGIN_REQUIRED", "Instagram Session 无效")) from error
    if not username:
        raise RuntimeError(_error_json("LOGIN_REQUIRED", "Instagram Session 无效"))
    return {"username": username, "cookies": cookies}


def _validated_cookies(value: Any) -> dict[str, str]:
    if not isinstance(value, dict):
        raise RuntimeError(_error_json("LOGIN_INCOMPLETE", "Instagram Cookie 格式无效"))
    cookies = {
        str(name).strip(): str(cookie_value)
        for name, cookie_value in value.items()
        if str(name).strip()
    }
    if not cookies.get("sessionid") or not cookies.get("csrftoken"):
        raise RuntimeError(
            _error_json("LOGIN_INCOMPLETE", "Instagram 登录尚未完成，请继续登录")
        )
    return cookies


def _session_payload(username: str, cookies: dict[str, str]) -> dict[str, Any]:
    return {
        "username": str(username).strip(),
        "cookies": _validated_cookies(cookies),
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


def _classify_error(error: Exception, *, authenticated: bool) -> tuple[str, str]:
    chain = _exception_chain(error)
    message = "\n".join(str(item).lower() for item in chain)

    if any(
        isinstance(item, instaloader_exceptions.LoginRequiredException)
        for item in chain
    ):
        return "LOGIN_REQUIRED", "Instagram 要求登录"
    if any(
        isinstance(item, instaloader_exceptions.TooManyRequestsException)
        for item in chain
    ) or "feedback_required" in message:
        return "RATE_LIMITED", "Instagram 请求过于频繁，请稍后重试"
    if authenticated and any(
        isinstance(item, instaloader_exceptions.AbortDownloadException)
        for item in chain
    ) and any(
        marker in message
        for marker in (
            "redirected to login page",
            "accounts/login",
            "logged out",
            "login_required",
            "checkpoint_required",
            "challenge_required",
        )
    ):
        return "LOGIN_REQUIRED", "Instagram 登录已失效，请重新登录"
    if any(
        isinstance(item, instaloader_exceptions.PrivateProfileNotFollowedException)
        for item in chain
    ):
        return "POST_UNAVAILABLE", "当前登录账号无权访问该帖子"
    if any(
        isinstance(
            item,
            (
                instaloader_exceptions.BadResponseException,
                instaloader_exceptions.QueryReturnedNotFoundException,
            ),
        )
        for item in chain
    ):
        return "POST_UNAVAILABLE", "帖子不存在、已失效或当前不可访问"
    if any(
        isinstance(
            item,
            (
                instaloader_exceptions.ConnectionException,
                instaloader_exceptions.QueryReturnedBadRequestException,
            ),
        )
        for item in chain
    ):
        return "NETWORK_ERROR", "连接 Instagram 失败，请检查系统网络或 VPN"
    return "INSTAGRAM_ERROR", "Instagram 帖子解析失败"


def _exception_chain(error: BaseException) -> list[BaseException]:
    pending = [error]
    seen: set[int] = set()
    chain: list[BaseException] = []
    while pending:
        current = pending.pop()
        identity = id(current)
        if identity in seen:
            continue
        seen.add(identity)
        chain.append(current)
        if current.__context__ is not None:
            pending.append(current.__context__)
        if current.__cause__ is not None:
            pending.append(current.__cause__)
    return chain


def _error_json(code: str, message: str) -> str:
    return json.dumps({"code": code, "message": message}, ensure_ascii=False, separators=(",", ":"))
