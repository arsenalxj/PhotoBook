from __future__ import annotations

import json
import re
from typing import Any
from urllib.parse import quote, urljoin, urlparse, urlunparse

import requests


_ALLOWED_HOSTS = {
    "xhslink.com",
    "www.xhslink.com",
    "xhslink.cn",
    "www.xhslink.cn",
    "xiaohongshu.com",
    "www.xiaohongshu.com",
    "rednote.com",
    "www.rednote.com",
}
_MEDIA_CDN_SUFFIXES = ("xhscdn.com", "xhscdn.net", "xhsimg.com")
_NOTE_ID_PATTERN = re.compile(r"/(?:explore|discovery/item)/([0-9a-f]{16,32})(?:/|$)", re.I)
_STATE_MARKERS = ("window.__INITIAL_STATE__", "window.__INITIAL_SSR_STATE__")
_WEBPIC_HOST_PATTERN = re.compile(r"^sns-webpic(?:-[a-z0-9-]+)?\.xhscdn\.(?:com|net)$", re.I)
_USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
    "Chrome/126 Mobile Safari/537.36"
)


def fetch_post(request_url: str) -> str:
    try:
        final_url, html = _fetch_html(request_url)
        note = _find_note(_parse_initial_state(html), _note_id_from_url(final_url))
        payload = _note_payload(note, final_url)
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    except RuntimeError:
        raise
    except requests.Timeout as error:
        raise RuntimeError(_error_json("NETWORK_ERROR", "小红书请求超时，请稍后重试")) from error
    except requests.RequestException as error:
        raise RuntimeError(_error_json("NETWORK_ERROR", "小红书请求失败，请检查系统网络或 VPN")) from error
    except Exception as error:
        raise RuntimeError(_error_json("INVALID_RESPONSE", "小红书返回的数据无法解析")) from error


def _fetch_html(request_url: str) -> tuple[str, str]:
    current = _validated_url(request_url)
    session = requests.Session()
    headers = {"User-Agent": _USER_AGENT, "Accept": "text/html,application/xhtml+xml"}
    for _ in range(6):
        response = session.get(current, headers=headers, timeout=30.0, allow_redirects=False)
        if response.status_code in (301, 302, 303, 307, 308):
            location = response.headers.get("Location")
            if not location:
                raise RuntimeError(_error_json("INVALID_URL", "小红书短链跳转无效"))
            current = _validated_url(urljoin(current, location))
            continue
        if response.status_code == 429:
            raise RuntimeError(_error_json("RATE_LIMITED", "小红书访问频率受限，请稍后重试"))
        if response.status_code in (404, 410):
            raise RuntimeError(_error_json("POST_UNAVAILABLE", "小红书帖子不存在或已失效"))
        if response.status_code >= 500:
            raise RuntimeError(_error_json("NETWORK_ERROR", "小红书暂时不可用，请稍后重试"))
        if response.status_code != 200:
            raise RuntimeError(_error_json("POST_UNAVAILABLE", "当前小红书帖子无法公开访问"))
        html = response.text
        if "验证" in html and "__INITIAL_STATE__" not in html:
            raise RuntimeError(_error_json("VERIFICATION_REQUIRED", "小红书要求验证，当前匿名模式无法访问"))
        return current, html
    raise RuntimeError(_error_json("INVALID_URL", "小红书短链跳转次数过多"))


def _validated_url(value: str) -> str:
    parsed = urlparse(str(value).strip())
    if parsed.scheme.lower() != "https" or (parsed.hostname or "").lower() not in _ALLOWED_HOSTS:
        raise RuntimeError(_error_json("INVALID_URL", "仅支持小红书公开分享链接"))
    try:
        port = parsed.port
    except ValueError as error:
        raise RuntimeError(_error_json("INVALID_URL", "小红书链接格式无效")) from error
    if parsed.username or parsed.password or port not in (None, 443):
        raise RuntimeError(_error_json("INVALID_URL", "小红书链接格式无效"))
    return value


def _parse_initial_state(html: str) -> dict[str, Any]:
    for marker in _STATE_MARKERS:
        start = html.find(marker)
        if start < 0:
            continue
        equals = html.find("=", start + len(marker))
        if equals < 0:
            continue
        raw = _balanced_object(html, equals + 1)
        if raw is not None:
            try:
                value = json.loads(_replace_undefined(raw))
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict):
                return value
    raise RuntimeError(_error_json("INVALID_RESPONSE", "小红书页面没有可解析的帖子数据"))


def _balanced_object(text: str, offset: int) -> str | None:
    start = text.find("{", offset)
    if start < 0:
        return None
    depth = 0
    quote: str | None = None
    escaped = False
    for index in range(start, len(text)):
        char = text[index]
        if quote is not None:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char in ('"', "'"):
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start : index + 1]
    return None


def _replace_undefined(raw: str) -> str:
    return re.sub(r"(?<![\w\"'])undefined(?![\w\"'])", "null", raw)


def _find_note(
    state: dict[str, Any], expected_note_id: str | None = None
) -> dict[str, Any]:
    candidates: list[dict[str, Any]] = []
    detail_map = _dig(state, "note", "noteDetailMap")
    if isinstance(detail_map, dict):
        for entry in detail_map.values():
            if isinstance(entry, dict):
                candidate = entry.get("note") or entry.get("noteData") or entry
                if isinstance(candidate, dict) and _note_id(candidate):
                    candidates.append(candidate)
    for path in (("noteData", "data", "noteData"), ("noteData", "noteData")):
        candidate = _dig(state, *path)
        if isinstance(candidate, dict) and _note_id(candidate):
            candidates.append(candidate)
    if expected_note_id:
        match = next(
            (candidate for candidate in candidates if _note_id(candidate) == expected_note_id),
            None,
        )
        if match is not None:
            return match
        raise RuntimeError(_error_json("SOURCE_MISMATCH", "小红书页面返回了不同的帖子"))
    if candidates:
        return candidates[0]
    raise RuntimeError(_error_json("POST_UNAVAILABLE", "小红书帖子不存在或无法公开访问"))


def _note_id_from_url(value: str) -> str | None:
    match = _NOTE_ID_PATTERN.search(urlparse(value).path)
    return match.group(1) if match else None


def _note_payload(note: dict[str, Any], final_url: str) -> dict[str, Any]:
    note_id = _required_text(_note_id(note), "帖子编号")
    user = note.get("user") if isinstance(note.get("user"), dict) else {}
    user_id = _optional_text(user.get("userId") or user.get("user_id") or user.get("id")) or "unknown"
    display_name = _optional_text(user.get("nickname") or user.get("nickName")) or user_id
    image_list = note.get("imageList") or note.get("images") or []
    video = note.get("video") if isinstance(note.get("video"), dict) else {}
    video_url = _video_url(video)
    note_type = (_optional_text(note.get("type") or note.get("noteType")) or "").lower()
    media: list[dict[str, Any]] = []
    sort_index = 0
    if note_type == "video":
        if video_url:
            media.append(_media(0, 0, "primary", "video", video_url, video))
    elif isinstance(image_list, list):
        for logical_index, image in enumerate(image_list):
            if not isinstance(image, dict):
                continue
            still_url, fallback_url = _image_urls(image)
            if still_url:
                live_url = _live_url(image)
                media.append(
                    _media(
                        sort_index,
                        logical_index,
                        "live_still" if live_url else "primary",
                        "image",
                        still_url,
                        image,
                        fallback_url,
                    )
                )
                sort_index += 1
                if live_url:
                    media.append(_media(sort_index, logical_index, "live_motion", "video", live_url, image))
                    sort_index += 1
    if not media:
        if video_url and note_type != "video":
            media.append(_media(0, 0, "primary", "video", video_url, video))
    if not media:
        raise RuntimeError(_error_json("INVALID_RESPONSE", "小红书帖子没有可下载媒体"))
    published = note.get("time") or note.get("publishTime") or note.get("lastUpdateTime")
    published_at = int(published) if isinstance(published, (int, float)) and published > 0 else 1
    canonical = f"https://www.xiaohongshu.com/explore/{note_id}"
    return {
        "sourcePlatform": "xiaohongshu",
        "sourcePostId": note_id,
        "sourceUrl": canonical,
        "authorUsername": user_id,
        "authorDisplayName": display_name,
        "authorProfileUrl": f"https://www.xiaohongshu.com/user/profile/{user_id}",
        "authorAvatarUrl": _upgrade_official_media_url(
            _optional_text(user.get("avatar") or user.get("image"))
        ),
        "caption": _optional_text(note.get("desc") or note.get("title")) or "",
        "publishedAt": published_at,
        "locationName": _optional_text(note.get("ipLocation") or note.get("location")),
        "media": media,
    }


def _media(
    sort_index: int,
    logical_index: int,
    role: str,
    media_type: str,
    url: str,
    raw: dict[str, Any],
    fallback_url: str | None = None,
) -> dict[str, Any]:
    payload = {
        "sortIndex": sort_index,
        "logicalIndex": logical_index,
        "mediaRole": role,
        "mediaType": media_type,
        "url": _upgrade_official_media_url(url),
        "width": _positive_int(raw.get("width")),
        "height": _positive_int(raw.get("height")),
        "durationMs": None,
    }
    upgraded_fallback = _upgrade_official_media_url(fallback_url)
    if upgraded_fallback and upgraded_fallback != payload["url"]:
        payload["fallbackUrl"] = upgraded_fallback
    return payload


def _image_url(image: dict[str, Any]) -> str | None:
    return _image_urls(image)[0]


def _image_urls(image: dict[str, Any]) -> tuple[str | None, str | None]:
    detail_url = _detail_image_url(image)
    original_url = _original_image_url(image, detail_url)
    if original_url:
        return original_url, detail_url
    return detail_url, None


def _detail_image_url(image: dict[str, Any]) -> str | None:
    info = image.get("infoList")
    if isinstance(info, list):
        candidates = [item for item in info if isinstance(item, dict) and _optional_text(item.get("url"))]
        for scene in ("WB_DFT", "H5_DTL", "CRD_DFT", "WB_DTL"):
            preferred = next((item for item in candidates if item.get("imageScene") == scene), None)
            if preferred:
                return _optional_text(preferred.get("url"))
        detail = next(
            (
                item
                for item in candidates
                if not str(item.get("imageScene") or "").upper().endswith("_PRV")
            ),
            None,
        )
        if detail:
            return _optional_text(detail.get("url"))
    fallback = _optional_text(image.get("urlDefault") or image.get("url"))
    if fallback:
        return fallback
    if isinstance(info, list):
        candidates = [item for item in info if isinstance(item, dict) and _optional_text(item.get("url"))]
        if candidates:
            return _optional_text(candidates[0].get("url"))
    return _optional_text(image.get("urlPre"))


def _original_image_url(image: dict[str, Any], detail_url: str | None) -> str | None:
    file_id = _optional_text(image.get("fileId") or image.get("file_id"))
    if not file_id or not detail_url:
        return None
    normalized_file_id = file_id.strip("/")
    segments = normalized_file_id.split("/")
    if not normalized_file_id or any(segment in ("", ".", "..") for segment in segments):
        return None
    parsed = urlparse(detail_url)
    host = (parsed.hostname or "").lower()
    if parsed.scheme.lower() not in ("http", "https") or not _WEBPIC_HOST_PATTERN.fullmatch(host):
        return None
    try:
        port = parsed.port
    except ValueError:
        return None
    if parsed.username or parsed.password or port not in (None, 80, 443):
        return None
    raw_host = host.replace("sns-webpic", "sns-img", 1)
    encoded_file_id = quote(normalized_file_id, safe="/-_.~")
    return f"https://{raw_host}/{encoded_file_id}?imageView2/2/format/jpg"


def _live_url(image: dict[str, Any]) -> str | None:
    for key in ("livePhoto", "live_photo", "video"):
        value = image.get(key)
        if isinstance(value, str):
            return _optional_text(value)
        if isinstance(value, dict):
            found = _video_url(value)
            if found:
                return found
    stream = image.get("stream")
    return _video_url({"media": {"stream": stream}}) if isinstance(stream, dict) else None


def _video_url(video: dict[str, Any]) -> str | None:
    for key in ("masterUrl", "master_url", "url"):
        found = _optional_text(video.get(key))
        if found:
            return found
    stream = _dig(video, "media", "stream") or video.get("stream")
    if isinstance(stream, dict):
        for codec in ("h264", "h265", "av1"):
            variants = stream.get(codec)
            if isinstance(variants, list):
                for variant in reversed(variants):
                    if isinstance(variant, dict):
                        found = _optional_text(variant.get("masterUrl") or variant.get("url"))
                        if found:
                            return found
    return None


def _note_id(note: dict[str, Any]) -> str | None:
    return _optional_text(note.get("noteId") or note.get("note_id") or note.get("id"))


def _dig(value: Any, *keys: str) -> Any:
    current = value
    for key in keys:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def _positive_int(value: Any) -> int | None:
    return int(value) if isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0 else None


def _optional_text(value: Any) -> str | None:
    normalized = str(value).strip() if value is not None else ""
    return normalized or None


def _upgrade_official_media_url(value: str | None) -> str | None:
    if value is None:
        return None
    parsed = urlparse(value)
    host = (parsed.hostname or "").lower()
    if parsed.scheme.lower() != "http" or not any(
        host == suffix or host.endswith(f".{suffix}") for suffix in _MEDIA_CDN_SUFFIXES
    ):
        return value
    try:
        port = parsed.port
    except ValueError:
        return value
    if parsed.username or parsed.password or port is not None:
        return value
    return urlunparse(parsed._replace(scheme="https"))


def _required_text(value: Any, field: str) -> str:
    normalized = _optional_text(value)
    if normalized is None:
        raise RuntimeError(_error_json("INVALID_RESPONSE", f"小红书未返回{field}"))
    return normalized


def _error_json(code: str, message: str) -> str:
    return json.dumps({"code": code, "message": message}, ensure_ascii=False, separators=(",", ":"))
