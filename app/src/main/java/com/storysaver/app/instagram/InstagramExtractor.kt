package com.storysaver.app.instagram

import com.storysaver.app.model.MediaType
import com.storysaver.app.util.UrlCheck
import com.storysaver.app.util.UrlValidator
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.UnknownHostException

/**
 * Resolves an Instagram link to downloadable media using only public, no-login pages.
 *
 *  1. The public embed page (what websites use to show a post). Instagram includes the
 *     post's media in it when the request comes from an app rather than a web browser.
 *  2. Fallback: Instagram's web GraphQL endpoint, anonymously. This often answers
 *     "login required", in which case we report that rather than trying to get around it.
 *
 * Never logs in, never sends cookies from a user session, never retries around a block.
 * No Android dependencies, so it is exercised directly by JVM tests (including a live test).
 */
class InstagramExtractor(private val client: OkHttpClient) {

    sealed interface Result {
        data class Success(val post: IgPost) : Result
        data class Failure(val message: String, val reason: Reason) : Result
    }

    enum class Reason { UNAVAILABLE, LOGIN_REQUIRED, RATE_LIMITED, NETWORK, UNSUPPORTED, PARSE }

    /** Blocking — call from a background thread (the ViewModel uses Dispatchers.IO). */
    fun resolve(check: UrlCheck): Result = try {
        when (check) {
            is UrlCheck.Post -> resolveShortcode(check.shortcode)
            is UrlCheck.ShareLink -> resolveShareLink(check.url)
            is UrlCheck.DirectMedia -> directMedia(check)
            is UrlCheck.StoryPage -> Result.Failure(STORY_MESSAGE, Reason.UNSUPPORTED)
            else -> Result.Failure("This isn't a link to an Instagram post or reel.", Reason.UNSUPPORTED)
        }
    } catch (e: RateLimited) {
        Result.Failure(e.message ?: "Instagram is limiting requests right now.", Reason.RATE_LIMITED)
    } catch (e: UnknownHostException) {
        Result.Failure("No internet connection, or Instagram couldn't be reached.", Reason.NETWORK)
    } catch (e: SocketTimeoutException) {
        Result.Failure("Instagram took too long to respond. Check your connection and try again.", Reason.NETWORK)
    } catch (e: IOException) {
        Result.Failure("Couldn't reach Instagram: ${e.message ?: "network error"}", Reason.NETWORK)
    }

    private fun directMedia(check: UrlCheck.DirectMedia): Result {
        val type = check.guessedType ?: MediaType.IMAGE
        return Result.Success(IgPost(null, null, listOf(IgMediaItem(0, type, check.url, null))))
    }

    /** instagram.com/share/… links redirect to the real post; follow them and classify the target. */
    private fun resolveShareLink(url: String): Result {
        val request = Request.Builder().url(url).header("User-Agent", APP_UA).build()
        val finalUrl = client.newCall(request).execute().use { it.request.url.toString() }
        val target = loginNextTarget(finalUrl) ?: finalUrl
        return when (val c = UrlValidator.check(target)) {
            is UrlCheck.Post -> resolveShortcode(c.shortcode)
            is UrlCheck.StoryPage -> Result.Failure(STORY_MESSAGE, Reason.UNSUPPORTED)
            else -> Result.Failure(
                "This share link didn't lead to a post or reel. Open it in Instagram, tap Share → Copy link, and paste that.",
                Reason.UNSUPPORTED,
            )
        }
    }

    fun resolveShortcode(shortcode: String): Result {
        // 1) Embed page
        var embedVerdict: IgParse = IgParse.NoData
        for (kind in listOf("p", "reel")) {
            val html = get("https://www.instagram.com/$kind/$shortcode/embed/captioned/") ?: continue
            embedVerdict = InstagramParser.parseEmbed(html)
            if (embedVerdict is IgParse.Found) return Result.Success((embedVerdict as IgParse.Found).post)
            if (embedVerdict is IgParse.Unavailable) break
        }

        // 2) Anonymous GraphQL fallback
        when (val g = graphql(shortcode)) {
            is IgParse.Found -> return Result.Success(g.post)
            is IgParse.Unavailable -> return Result.Failure(g.reason, Reason.UNAVAILABLE)
            else -> Unit
        }

        return when (val v = embedVerdict) {
            is IgParse.Unavailable -> Result.Failure(v.reason, Reason.UNAVAILABLE)
            else -> Result.Failure(
                "Instagram didn't share this post's media without logging in. It may be private or " +
                    "restricted, or Instagram may be limiting requests right now — try again in a few minutes.",
                Reason.LOGIN_REQUIRED,
            )
        }
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", APP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code == 429) throw RateLimited()
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private fun graphql(shortcode: String): IgParse {
        return try {
            val variables = JSONObject()
                .put("shortcode", shortcode)
                .put("fetch_tagged_user_count", JSONObject.NULL)
                .put("hoisted_comment_id", JSONObject.NULL)
                .put("hoisted_reply_id", JSONObject.NULL)
            val body = FormBody.Builder()
                .add("av", "0").add("__d", "www").add("__user", "0").add("__a", "1")
                .add("fb_api_caller_class", "RelayModern")
                .add("fb_api_req_friendly_name", "PolarisPostActionLoadPostQueryQuery")
                .add("variables", variables.toString())
                .add("server_timestamps", "true")
                .add("doc_id", GRAPHQL_DOC_ID)
                .build()
            val request = Request.Builder()
                .url("https://www.instagram.com/graphql/query")
                .post(body)
                .header("User-Agent", APP_UA)
                .header("X-IG-App-ID", WEB_APP_ID)
                .header("X-FB-Friendly-Name", "PolarisPostActionLoadPostQueryQuery")
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) IgParse.NoData else InstagramParser.parseGraphql(text)
            }
        } catch (e: IOException) {
            IgParse.NoData
        }
    }

    private class RateLimited : IOException("Instagram is limiting requests right now. Please wait a few minutes and try again.")

    companion object {
        /** An honest app identifier. Instagram serves the embed's media data to non-browser clients. */
        const val APP_UA = "StorySaver/1.1 (Android)"
        private const val WEB_APP_ID = "936619743392459"
        private const val GRAPHQL_DOC_ID = "8845758582119845"

        const val STORY_MESSAGE =
            "Instagram Stories can't be downloaded from a link. Instagram only shows stories to people who are " +
                "logged in, and Story Saver doesn't log in to Instagram or ask for your password.\n\n" +
                "Reels and posts from public accounts work — paste their link instead. For your own stories, " +
                "use Instagram's Archive → Save."

        /** instagram.com/accounts/login/?next=/reel/XYZ/ → https://www.instagram.com/reel/XYZ/ */
        fun loginNextTarget(url: String): String? {
            if (!url.contains("/accounts/login")) return null
            val next = url.substringAfter("next=", "").substringBefore('&')
            if (next.isEmpty()) return null
            val path = runCatching { URLDecoder.decode(next, "UTF-8") }.getOrNull() ?: return null
            return if (path.startsWith("/")) "https://www.instagram.com$path" else path
        }
    }
}
