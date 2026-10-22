"""Probe which no-login methods return media for a public Instagram post. Stdlib only."""
import http.cookiejar
import json
import re
import sys
import urllib.parse
import urllib.request

SHORTCODES = sys.argv[1:] or ["DeyQf_PzHlD"]
UA_DESKTOP = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
UA_MOBILE = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")

out = []


def log(*a):
    s = " ".join(str(x) for x in a)
    print(s)
    out.append(s)


def opener():
    cj = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj)), cj


def req(op, url, headers=None, data=None):
    r = urllib.request.Request(url, data=data, headers=headers or {})
    try:
        with op.open(r, timeout=25) as resp:
            body = resp.read().decode("utf-8", "replace")
            return resp.status, resp.geturl(), body
    except urllib.error.HTTPError as e:
        return e.code, url, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa
        return -1, url, repr(e)


def summarize(body):
    keys = ["video_url", "video_versions", "display_url", "og:video", "og:image", "contextJSON",
            "EmbeddedMediaImage", "xdt_shortcode_media", "shortcode_media", "loginForm",
            "Log in", "require_login", "checkpoint", "\"lsd\"", "csrf_token", "is_video", "edge_sidecar"]
    return {k: body.count(k) for k in keys if k in body}


def find_urls(body):
    found = set()
    for m in re.finditer(r'"(video_url|display_url|url)"\s*:\s*"(https:[^"]+?)"', body):
        u = m.group(2).encode().decode("unicode_escape").replace("\\/", "/")
        if "cdninstagram" in u or "fbcdn" in u:
            found.add((m.group(1), u[:140]))
    for m in re.finditer(r'<meta[^>]+property="og:(video|image)(?::secure_url)?"[^>]+content="([^"]+)"', body):
        found.add(("og:" + m.group(1), m.group(2)[:140]))
    for m in re.finditer(r'<video[^>]+src="([^"]+)"', body):
        found.add(("video-tag", m.group(1)[:140]))
    return sorted(found)[:8]


for sc in SHORTCODES:
    log("=" * 70)
    log("SHORTCODE", sc)

    # 1. Post page itself (desktop + mobile UA)
    for name, ua in [("desktop", UA_DESKTOP), ("mobile", UA_MOBILE)]:
        op, cj = opener()
        st, final, body = req(op, f"https://www.instagram.com/reel/{sc}/", {"User-Agent": ua, "Accept-Language": "en-US,en;q=0.9"})
        log(f"[page/{name}] status={st} final={final} len={len(body)} {summarize(body)}")
        log("   urls:", find_urls(body))
        log("   cookies:", [c.name for c in cj])

    # 2. Embed pages
    for path in ["embed/captioned/", "embed/"]:
        for kind in ["p", "reel"]:
            op, _ = opener()
            st, final, body = req(op, f"https://www.instagram.com/{kind}/{sc}/{path}", {"User-Agent": UA_DESKTOP})
            log(f"[embed {kind}/{path}] status={st} len={len(body)} {summarize(body)}")
            log("   urls:", find_urls(body))
            m = re.search(r'"contextJSON":"((?:\\.|[^"\\])*)"', body)
            if m:
                try:
                    ctx = json.loads(json.loads('"' + m.group(1) + '"'))
                    media = ((ctx.get("gql_data") or {}).get("shortcode_media")) or {}
                    log("   contextJSON keys:", list(ctx.keys())[:10], "media keys:", list(media.keys())[:15])
                    log("   is_video:", media.get("is_video"), "video_url:", str(media.get("video_url"))[:120])
                except Exception as e:  # noqa
                    log("   contextJSON parse error", e)

    # 3. GraphQL (PolarisPostActionLoadPostQueryQuery), with tokens from the page
    for doc_id in ["8845758582119845", "25531498899829322", "10015901848480474"]:
        op, cj = opener()
        st, _, page = req(op, f"https://www.instagram.com/p/{sc}/", {"User-Agent": UA_DESKTOP})
        csrf = next((c.value for c in cj if c.name == "csrftoken"), "")
        lsd_m = re.search(r'"LSD",\[\],\{"token":"([^"]+)"', page) or re.search(r'"lsd":"([^"]+)"', page)
        lsd = lsd_m.group(1) if lsd_m else "AVqbxe3J_YA"
        variables = json.dumps({"shortcode": sc, "fetch_tagged_user_count": None,
                                "hoisted_comment_id": None, "hoisted_reply_id": None})
        form = urllib.parse.urlencode({
            "av": "0", "__d": "www", "__user": "0", "__a": "1", "lsd": lsd,
            "fb_api_caller_class": "RelayModern",
            "fb_api_req_friendly_name": "PolarisPostActionLoadPostQueryQuery",
            "variables": variables, "server_timestamps": "true", "doc_id": doc_id,
        }).encode()
        headers = {
            "User-Agent": UA_DESKTOP, "Content-Type": "application/x-www-form-urlencoded",
            "X-IG-App-ID": "936619743392459", "X-FB-LSD": lsd, "X-ASBD-ID": "129477",
            "X-CSRFToken": csrf, "X-FB-Friendly-Name": "PolarisPostActionLoadPostQueryQuery",
            "Origin": "https://www.instagram.com", "Referer": f"https://www.instagram.com/p/{sc}/",
            "Sec-Fetch-Site": "same-origin",
        }
        st, _, body = req(op, "https://www.instagram.com/graphql/query", headers, form)
        log(f"[graphql doc={doc_id}] csrf={'y' if csrf else 'n'} lsd={'page' if lsd_m else 'default'} status={st} len={len(body)} {summarize(body)}")
        log("   body head:", body[:300].replace("\n", " "))
        log("   urls:", find_urls(body))

    # 4. Legacy JSON endpoints
    for url in [f"https://www.instagram.com/p/{sc}/?__a=1&__d=dis",
                "https://www.instagram.com/graphql/query/?query_hash=b3055c01b4b222b8a47dc12b090e4e64&variables="
                + urllib.parse.quote(json.dumps({"shortcode": sc}))]:
        op, _ = opener()
        st, _, body = req(op, url, {"User-Agent": UA_DESKTOP, "X-IG-App-ID": "936619743392459"})
        log(f"[legacy {url[:60]}] status={st} len={len(body)} {summarize(body)}")
        log("   head:", body[:200].replace("\n", " "))

with open("probe-report.txt", "w") as f:
    f.write("\n".join(out))
