"""One-off probe #2: structure of the embed page's contextJSON, and whether the media URL downloads.
Printed as ::notice:: annotations (publicly readable)."""
import json
import re
import urllib.request

UA = "StorySaver/1.0 (Android)"
URLS = ["https://www.instagram.com/reel/DeyQf_PzHlD/embed/captioned/"]


def note(msg: str) -> None:
    print("::notice::" + msg.replace("\n", " ").replace("%", "%25")[:3800])


def get(url: str, rng: bool = False):
    h = {"User-Agent": UA}
    if rng:
        h["Range"] = "bytes=0-15"
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.status, dict(r.headers), r.read()


def paths(obj, prefix="", out=None, depth=0):
    if out is None:
        out = []
    if depth > 7:
        return out
    if isinstance(obj, dict):
        for k, v in obj.items():
            p = f"{prefix}.{k}" if prefix else k
            if k in ("video_url", "display_url", "is_video", "__typename", "shortcode", "edge_sidecar_to_children",
                     "video_versions", "image_versions2", "media", "shortcode_media", "product_type"):
                out.append(p if not isinstance(v, (str, bool, int)) or k in ("video_url", "display_url") else f"{p}={str(v)[:40]}")
            paths(v, p, out, depth + 1)
    elif isinstance(obj, list):
        for i, v in enumerate(obj[:3]):
            paths(v, f"{prefix}[{i}]", out, depth + 1)
    return out


for url in URLS:
    status, headers, raw = get(url)
    body = raw.decode("utf-8", "replace")
    i = body.find("contextJSON")
    note(f"{url} status={status} ctype={headers.get('Content-Type')} contextJSON@{i}")
    note("BEFORE contextJSON: " + re.sub(r"(oh|oe|_nc_[a-z]+)=[^&\"\\]+", r"\1=…", body[max(0, i - 400):i + 60]))
    # The value is a JSON string literal; decode it with a JSON parser.
    m = re.search(r'"contextJSON":("(?:[^"\\]|\\.)*")', body)
    if not m:
        note("contextJSON string literal NOT matched by regex")
        continue
    inner_text = json.loads(m.group(1))
    note(f"inner text length={len(inner_text)} starts={inner_text[:80]}")
    inner = json.loads(inner_text)
    note("top-level keys: " + ", ".join(list(inner.keys())[:30]))
    found = paths(inner)
    note("paths: " + " | ".join(found[:40]))
    # find video_url and test the download
    vurl = None

    def find(o):
        global vurl
        if vurl:
            return
        if isinstance(o, dict):
            if isinstance(o.get("video_url"), str):
                vurl = o["video_url"]
                return
            for v in o.values():
                find(v)
        elif isinstance(o, list):
            for v in o:
                find(v)

    find(inner)
    if vurl:
        try:
            s, h, b = get(vurl, rng=True)
            note(f"media GET status={s} ctype={h.get('Content-Type')} len={h.get('Content-Length')} "
                 f"range={h.get('Content-Range')} head={b[:12].hex()}")
        except Exception as e:  # noqa: BLE001
            note(f"media GET error: {e}")
