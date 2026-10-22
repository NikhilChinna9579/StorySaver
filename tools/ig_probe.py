"""One-off probe: what does Instagram return to a plain, logged-out request for a public reel?
Results are printed as GitHub Actions ::notice:: annotations so they are publicly readable."""
import re
import urllib.request

CODE = "DeyQf_PzHlD"
UAS = {
    "app": "StorySaver/1.0 (Android)",
    "mobile-browser": "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                      "(KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36",
}
URLS = [
    f"https://www.instagram.com/reel/{CODE}/embed/",
    f"https://www.instagram.com/reel/{CODE}/embed/captioned/",
    f"https://www.instagram.com/p/{CODE}/embed/captioned/",
    f"https://www.instagram.com/reel/{CODE}/",
]


def note(msg: str) -> None:
    print("::notice::" + msg.replace("\n", " ").replace("%", "%25")[:3500])


for ua_name, ua in UAS.items():
    for url in URLS:
        req = urllib.request.Request(url, headers={"User-Agent": ua, "Accept-Language": "en-US,en;q=0.9"})
        try:
            with urllib.request.urlopen(req, timeout=25) as r:
                status, final, body = r.status, r.geturl(), r.read().decode("utf-8", "replace")
        except Exception as e:  # noqa: BLE001
            note(f"[{ua_name}] {url} -> ERROR {e}")
            continue
        counts = {k: body.count(k) for k in [
            "video_url", "video_versions", ".mp4", "<video", "og:video", "display_url",
            "EmbedIsBroken", "login", "shortcode_media", "contextJSON", "is_video",
        ]}
        note(f"[{ua_name}] {url} -> {status} final={final} bytes={len(body)} counts={counts}")
        for key in ["video_url", "og:video", "<video", ".mp4"]:
            i = body.find(key)
            if i >= 0:
                snippet = body[max(0, i - 120): i + 260]
                snippet = re.sub(r"(oh|_nc_[a-z]+|efg|oe)=[^&\"\\]+", r"\1=…", snippet)
                note(f"[{ua_name}] snippet around {key!r}: {snippet}")
                break
