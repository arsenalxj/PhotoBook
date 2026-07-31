from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from html.parser import HTMLParser
from http.cookies import SimpleCookie
from typing import Any
from urllib.parse import urlparse

import instaloader
import requests
from instaloader import exceptions as instaloader_exceptions
from requests import exceptions as requests_exceptions


_SHORTCODE_PATTERN = re.compile(r"^[A-Za-z0-9_-]+$")
_FORK_COMMIT = "b1d233362e335cbbccba5c5e4b614a1032764118"
_POST_METADATA_DOC_ID = "27128499623469141"
_HTML_MEDIA_ERROR_CODE = "4630001"
_HTML_MEDIA_ERROR_DESCRIPTION = "Media should not be an HtmlResponse"
_SHORTCODE_WEB_INFO_ERROR_PATH = (
    "xdt_api__v1__media__shortcode__web_info",
)
_PUBLIC_POST_META_PROPERTIES = frozenset(
    {
        "og:title",
        "og:image",
        "og:description",
    }
)
_MEDIA_INFO_REQUIRED = object()


class _MetadataResponseContext:
    def __init__(self, context: Any, shortcode: str) -> None:
        self._context = context
        self._shortcode = shortcode
        self.response: dict[str, Any] | None = None

    def __getattr__(self, name: str) -> Any:
        return getattr(self._context, name)

    def doc_id_graphql_query(
        self,
        doc_id: str,
        variables: dict[str, Any],
        referer: str | None = None,
    ) -> dict[str, Any]:
        response = self._context.doc_id_graphql_query(doc_id, variables, referer)
        if doc_id == _POST_METADATA_DOC_ID and variables.get("shortcode") == self._shortcode:
            self.response = response
        return response


class _PublicPostPageParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.canonical_urls: set[str] = set()
        self.meta_properties: set[str] = set()

    def handle_starttag(
        self,
        tag: str,
        attrs: list[tuple[str, str | None]],
    ) -> None:
        attributes = dict(attrs)
        if tag == "link":
            rel = str(attributes.get("rel") or "").lower().split()
            href = str(attributes.get("href") or "").strip()
            if "canonical" in rel and href:
                self.canonical_urls.add(href)
            return
        if tag != "meta":
            return
        name = str(attributes.get("property") or "").lower().strip()
        content = str(attributes.get("content") or "").strip()
        if name in _PUBLIC_POST_META_PROPERTIES and content:
            self.meta_properties.add(name)


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
        code, message = _classify_validation_error(error)
        raise RuntimeError(_error_json(code, message)) from error


def fetch_post(shortcode: str, session_json: str | None = None) -> str:
    normalized = str(shortcode).strip()
    if not _SHORTCODE_PATTERN.fullmatch(normalized):
        raise RuntimeError(_error_json("INVALID_URL", "Instagram 帖子编号无效"))

    session = None
    try:
        loader, session = _loader_with_session(session_json)
        post = _fetch_post_metadata(
            loader,
            normalized,
            authenticated=session is not None,
        )
        if post is _MEDIA_INFO_REQUIRED:
            return json.dumps({"mediaInfoRequired": True}, separators=(",", ":"))
        return _fetch_result_json(loader, session, post, normalized)
    except RuntimeError:
        raise
    except Exception as error:
        code, message = _classify_error(error, authenticated=session is not None)
        raise RuntimeError(_error_json(code, message)) from error


def fetch_post_media_info(shortcode: str, session_json: str | None = None) -> str:
    normalized = str(shortcode).strip()
    if not _SHORTCODE_PATTERN.fullmatch(normalized):
        raise RuntimeError(_error_json("INVALID_URL", "Instagram 帖子编号无效"))

    session = None
    try:
        loader, session = _loader_with_session(session_json)
        if session is None:
            raise RuntimeError(_error_json("LOGIN_REQUIRED", "Instagram 要求登录"))
        post = _post_from_media_info(loader.context, normalized)
        return _fetch_result_json(loader, session, post, normalized)
    except RuntimeError:
        raise
    except Exception as error:
        code, message = _classify_error(error, authenticated=session is not None)
        raise RuntimeError(_error_json(code, message)) from error


def _loader_with_session(session_json: str | None) -> tuple[Any, dict[str, Any] | None]:
    loader = instaloader.Instaloader(
        max_connection_attempts=1,
        request_timeout=30.0,
    )
    session = _session_from_json(session_json)
    if session is not None:
        loader.load_session(session["username"], session["cookies"])
    return loader, session


def _fetch_result_json(
    loader: Any,
    session: dict[str, Any] | None,
    post: Any,
    shortcode: str,
) -> str:
    profile = post.owner_profile
    if bool(getattr(profile, "is_private", False)):
        raise RuntimeError(_error_json("POST_UNAVAILABLE", "当前版本只支持公开帖子"))
    return json.dumps(
        {
            "post": _post_payload(post, expected_shortcode=shortcode),
            "refreshedSession": (
                _session_payload(session["username"], loader.save_session())
                if session is not None
                else None
            ),
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )


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
        "sourcePlatform": "instagram",
        "sourcePostId": shortcode,
        "sourceUrl": f"https://www.instagram.com/{_post_kind(post, raw)}/{shortcode}/",
        "authorUsername": username,
        "authorDisplayName": display_name,
        "authorProfileUrl": f"https://www.instagram.com/{username}/",
        "authorAvatarUrl": _optional_text(getattr(profile, "profile_pic_url", None)),
        "caption": str(getattr(post, "caption", None) or ""),
        "publishedAt": _timestamp_ms(getattr(post, "date_utc", None)),
        "locationName": _location_name(_post_location(post, raw)),
        "media": media,
    }


def _fetch_post_metadata(loader: Any, shortcode: str, *, authenticated: bool) -> Any:
    context = _MetadataResponseContext(loader.context, shortcode)
    try:
        return instaloader.Post.from_shortcode(context, shortcode)
    except instaloader_exceptions.BadResponseException as error:
        is_html_media_response = _is_html_media_response(context.response)
        is_shortcode_execution_error = _is_shortcode_web_info_execution_error(
            context.response
        )
        if not is_html_media_response and not is_shortcode_execution_error:
            raise
        if (
            is_shortcode_execution_error
            and not is_html_media_response
            and not authenticated
            and not _anonymous_permalink_confirms_public_post(shortcode)
        ):
            raise
        if not authenticated:
            raise RuntimeError(
                _error_json("LOGIN_REQUIRED", "Instagram 要求登录以兼容解析该公开帖子")
            ) from error
        return _MEDIA_INFO_REQUIRED


def _post_from_media_info(context: Any, shortcode: str) -> Any:
    media_id = instaloader.Post.shortcode_to_mediaid(shortcode)
    response = context.get_iphone_json(
        path=f"api/v1/media/{media_id}/info/",
        params={},
    )
    items = response.get("items") if isinstance(response, dict) else None
    if not isinstance(items, list) or not items or not isinstance(items[0], dict):
        raise instaloader_exceptions.QueryReturnedNotFoundException(
            "Instagram authenticated media info did not return the post"
        )
    return instaloader.Post.from_iphone_struct(context, items[0])


def _is_html_media_response(response: Any) -> bool:
    if not isinstance(response, dict):
        return False
    errors = response.get("errors")
    if not isinstance(errors, list):
        return False
    for error in errors:
        if not isinstance(error, dict):
            continue
        if str(error.get("code")) != _HTML_MEDIA_ERROR_CODE:
            continue
        if _HTML_MEDIA_ERROR_DESCRIPTION in _error_descriptions(error):
            return True
    return False


def _is_shortcode_web_info_execution_error(response: Any) -> bool:
    if (
        not isinstance(response, dict)
        or response.get("status") != "ok"
        or "data" not in response
        or response["data"] is not None
    ):
        return False
    errors = response.get("errors")
    if not isinstance(errors, list):
        return False
    for error in errors:
        if not isinstance(error, dict):
            continue
        path = error.get("path")
        if (
            error.get("message") == "execution error"
            and error.get("severity") == "CRITICAL"
            and isinstance(path, list)
            and tuple(path) == _SHORTCODE_WEB_INFO_ERROR_PATH
        ):
            return True
    return False


def _anonymous_permalink_confirms_public_post(shortcode: str) -> bool:
    try:
        response = requests.get(
            f"https://www.instagram.com/p/{shortcode}/",
            timeout=30.0,
        )
    except requests_exceptions.RequestException as error:
        raise RuntimeError(
            _error_json("NETWORK_ERROR", "连接 Instagram 失败，请检查系统网络或 VPN")
        ) from error
    try:
        if response.status_code == 429:
            raise RuntimeError(
                _error_json("RATE_LIMITED", "Instagram 请求过于频繁，请稍后重试")
            )
        if response.status_code >= 500:
            raise RuntimeError(
                _error_json("NETWORK_ERROR", "连接 Instagram 失败，请检查系统网络或 VPN")
            )
        if response.status_code != 200:
            return False
        parser = _PublicPostPageParser()
        parser.feed(response.text)
    finally:
        response.close()
    return (
        _PUBLIC_POST_META_PROPERTIES <= parser.meta_properties
        and any(
            _canonical_matches_shortcode(url, shortcode)
            for url in parser.canonical_urls
        )
    )


def _canonical_matches_shortcode(url: str, shortcode: str) -> bool:
    parsed = urlparse(url)
    return (
        parsed.scheme == "https"
        and parsed.hostname == "www.instagram.com"
        and parsed.port is None
        and parsed.username is None
        and parsed.password is None
        and not parsed.query
        and not parsed.fragment
        and parsed.path
        in {
            f"/p/{shortcode}/",
            f"/reel/{shortcode}/",
            f"/tv/{shortcode}/",
        }
    )


def _error_descriptions(error: dict[str, Any]) -> set[str]:
    descriptions = {_optional_text(error.get("description"))}
    extensions = error.get("extensions")
    if isinstance(extensions, dict):
        additional = extensions.get("additional_info_do_not_use_except_for_migration")
        if isinstance(additional, str):
            try:
                parsed = json.loads(additional)
            except (TypeError, ValueError):
                parsed = None
            if isinstance(parsed, dict):
                descriptions.add(_optional_text(parsed.get("message")))
    return {item for item in descriptions if item is not None}


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
        "logicalIndex": index,
        "mediaRole": "primary",
        "mediaType": "video" if is_video else "image",
        "url": _required_text(url, f"第 {index + 1} 个媒体地址"),
        "width": width,
        "height": height,
        "durationMs": _duration_ms(raw) if is_video else None,
    }


def _dimensions(raw: Any) -> tuple[int | None, int | None]:
    media = _raw_media(raw)
    if not isinstance(media, dict):
        return None, None
    dimensions = media.get("dimensions")
    if isinstance(dimensions, dict):
        return _positive_int(dimensions.get("width")), _positive_int(dimensions.get("height"))
    width = _positive_int(media.get("original_width"))
    height = _positive_int(media.get("original_height"))
    if width is not None or height is not None:
        return width, height
    candidates = (media.get("image_versions2") or {}).get("candidates")
    first = candidates[0] if isinstance(candidates, list) and candidates else None
    if not isinstance(first, dict):
        return None, None
    return _positive_int(first.get("width")), _positive_int(first.get("height"))


def _duration_ms(raw: Any) -> int | None:
    media = _raw_media(raw)
    if not isinstance(media, dict):
        return None
    value = media.get("video_duration")
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value <= 0:
        return None
    return round(float(value) * 1000)


def _post_kind(post: Any, raw: Any) -> str:
    media = _raw_media(raw)
    product_type = str(media.get("product_type", "")) if isinstance(media, dict) else ""
    if getattr(post, "typename", "") == "GraphVideo" and product_type == "clips":
        return "reel"
    return "p"


def _raw_media(raw: Any) -> Any:
    if not isinstance(raw, dict):
        return raw
    iphone = raw.get("iphone_struct")
    return iphone if isinstance(iphone, dict) else raw


def _post_location(post: Any, raw: Any) -> Any:
    if isinstance(raw, dict) and isinstance(raw.get("iphone_struct"), dict):
        return raw["iphone_struct"].get("location")
    return _optional_attribute(post, "location")


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
        isinstance(
            item,
            (
                instaloader_exceptions.AbortDownloadException,
                instaloader_exceptions.ConnectionException,
                instaloader_exceptions.QueryReturnedBadRequestException,
            ),
        )
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
                requests_exceptions.ConnectionError,
                requests_exceptions.Timeout,
            ),
        )
        for item in chain
    ):
        return "NETWORK_ERROR", "连接 Instagram 失败，请检查系统网络或 VPN"
    return "INSTAGRAM_ERROR", "Instagram 帖子解析失败"


def _classify_validation_error(error: Exception) -> tuple[str, str]:
    chain = _exception_chain(error)
    message = "\n".join(str(item).lower() for item in chain)
    if any(
        isinstance(item, instaloader_exceptions.TooManyRequestsException)
        for item in chain
    ) or "feedback_required" in message:
        return "RATE_LIMITED", "Instagram 请求过于频繁，请稍后重试"
    if any(
        isinstance(
            item,
            (
                instaloader_exceptions.ConnectionException,
                requests_exceptions.ConnectionError,
                requests_exceptions.Timeout,
            ),
        )
        for item in chain
    ):
        return "NETWORK_ERROR", "连接 Instagram 失败，请检查系统网络或 VPN"
    return "LOGIN_VALIDATION_FAILED", "Instagram 登录状态验证失败"


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
