"""Small, non-logging yt-dlp adapter used by the Android WorkManager worker."""

from __future__ import annotations

import glob
import json
import os
from pathlib import Path

import certifi
import yt_dlp


class _QuietLogger:
    def debug(self, message):
        pass

    def info(self, message):
        pass

    def warning(self, message):
        pass

    def error(self, message):
        pass


def _error_code(message: str) -> str:
    lowered = message.lower()
    if "requested format is not available" in lowered:
        return "NO_SINGLE_FILE_FORMAT"
    if "po token" in lowered or "po_token" in lowered:
        return "PO_TOKEN_REQUIRED"
    if any(fragment in lowered for fragment in (
        "sign in", "login required", "log in", "cookies", "private video",
        "members-only", "members only", "age-restricted", "confirm your age",
    )):
        return "AUTH_REQUIRED"
    if any(fragment in lowered for fragment in (
        "timed out", "temporary failure", "connection reset", "network is unreachable",
        "unable to download webpage", "http error 5", "remote end closed",
    )):
        return "NETWORK"
    if "not available" in lowered or "unavailable" in lowered or "unsupported url" in lowered:
        return "UNAVAILABLE"
    return "EXTRACTOR_FAILED"


def _merged_ca_bundle(android_ca_path: str | None, output_path: str) -> str:
    target = Path(output_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("wb") as output:
        output.write(Path(certifi.where()).read_bytes())
        output.write(b"\n")
        if android_ca_path and Path(android_ca_path).is_file():
            output.write(Path(android_ca_path).read_bytes())
            output.write(b"\n")
    return str(target)


def _resolve_output(info: dict, output_template: str) -> str | None:
    requested = info.get("requested_downloads") or []
    for media in requested:
        path = media.get("filepath") or media.get("_filename")
        if path and Path(path).is_file():
            return str(Path(path).resolve())
    for key in ("filepath", "_filename"):
        path = info.get(key)
        if path and Path(path).is_file():
            return str(Path(path).resolve())

    prefix = output_template.replace(".%(ext)s", "")
    candidates = [
        path for path in glob.glob(prefix + ".*")
        if not path.endswith((".part", ".ytdl", ".json")) and Path(path).is_file()
    ]
    return str(Path(max(candidates, key=os.path.getmtime)).resolve()) if candidates else None


def _download_one(
    source_url: str,
    output_template: str,
    format_selector: str,
    common_options: dict,
    callback,
    progress_start: float,
    progress_span: float,
) -> tuple[dict, str]:
    def progress_hook(update):
        if callback.isCancelled():
            raise RuntimeError("DOWNLOAD_CANCELLED")
        if update.get("status") == "finished":
            callback.onProgress(progress_start + progress_span)
            return
        if update.get("status") != "downloading":
            return
        total = update.get("total_bytes") or update.get("total_bytes_estimate") or 0
        downloaded = update.get("downloaded_bytes") or 0
        if total:
            fraction = max(0.0, min(1.0, downloaded / total))
            callback.onProgress(progress_start + progress_span * fraction)

    options = dict(common_options)
    options.update({
        "format": format_selector,
        "outtmpl": output_template,
        "progress_hooks": [progress_hook],
    })
    with yt_dlp.YoutubeDL(options) as downloader:
        info = downloader.extract_info(source_url, download=True)
    output = _resolve_output(info, output_template)
    if not output or Path(output).stat().st_size <= 0:
        raise RuntimeError("yt-dlp completed without a playable output file")
    return info, output


def download(
    source_url: str,
    source: str,
    output_template: str,
    quickjs_path: str,
    android_ca_path: str,
    merged_ca_path: str,
    cookie_path: str | None,
    callback,
) -> str:
    """Download exactly one item and return a compact JSON result."""
    ca_bundle = _merged_ca_bundle(android_ca_path, merged_ca_path)
    os.environ["SSL_CERT_FILE"] = ca_bundle

    audio_only = source == "youtube-music"
    player_clients = (
        ["tv_downgraded", "web_safari", "visionos"]
        if audio_only else
        ["web_embedded", "tv", "tv_simply"]
    )
    common_options = {
        "noplaylist": True,
        "continuedl": True,
        "nopart": False,
        "overwrites": False,
        "writethumbnail": False,
        "writeinfojson": False,
        "writesubtitles": False,
        "quiet": True,
        "no_warnings": True,
        "logger": _QuietLogger(),
        "socket_timeout": 20,
        "retries": 3,
        "fragment_retries": 3,
        "compat_opts": {"no-certifi"},
        "js_runtimes": {"quickjs": {"path": quickjs_path}},
        "extractor_args": {
            "youtube": {
                "player_client": player_clients,
            },
        },
        "cookiefile": cookie_path if cookie_path and Path(cookie_path).is_file() else None,
    }

    try:
        Path(output_template).parent.mkdir(parents=True, exist_ok=True)
        os.chdir(Path(output_template).parent)
        if audio_only:
            info, output = _download_one(
                source_url,
                output_template,
                "bestaudio[acodec!=none]/bestaudio/best",
                common_options,
                callback,
                0.0,
                1.0,
            )
            return json.dumps({
                "ok": True,
                "path": output,
                "title": info.get("title"),
                "artist": info.get("artist") or info.get("uploader") or info.get("channel"),
                "durationMs": int(float(info["duration"]) * 1000) if info.get("duration") else None,
                "width": info.get("width"),
                "height": info.get("height"),
                "ext": info.get("ext"),
            })

        # Progressive YouTube streams are commonly limited to 360p. Download a high-quality
        # AVC MP4 video track and M4A audio track independently; Android's MediaMuxer combines
        # them without re-encoding, FFmpeg, normalization, or any volume processing.
        prefix = output_template.replace(".%(ext)s", "")
        video_info, video_output = _download_one(
            source_url,
            prefix + ".video.%(ext)s",
            (
                "bestvideo[height<=1080][ext=mp4][vcodec^=avc1]/"
                "bestvideo[height<=1080][ext=mp4]/"
                "best[height<=1080][ext=mp4][vcodec!=none]"
            ),
            common_options,
            callback,
            0.0,
            0.82,
        )
        audio_info, audio_output = _download_one(
            source_url,
            prefix + ".audio.%(ext)s",
            "bestaudio[ext=m4a][acodec^=mp4a]/bestaudio[ext=m4a]/best[ext=mp4]",
            common_options,
            callback,
            0.82,
            0.16,
        )
        return json.dumps({
            "ok": True,
            "videoPath": video_output,
            "audioPath": audio_output,
            "title": video_info.get("title") or audio_info.get("title"),
            "artist": (
                video_info.get("artist") or video_info.get("uploader") or
                video_info.get("channel") or audio_info.get("artist") or
                audio_info.get("uploader") or audio_info.get("channel")
            ),
            "durationMs": (
                int(float(video_info["duration"]) * 1000)
                if video_info.get("duration") else None
            ),
            "width": video_info.get("width"),
            "height": video_info.get("height"),
            "ext": "mp4",
        })
    except Exception as error:
        message = str(error)
        if message == "DOWNLOAD_CANCELLED":
            code = "CANCELLED"
        else:
            code = _error_code(message)
        return json.dumps({"ok": False, "code": code, "message": message[:500]})
