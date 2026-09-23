package dev.echo

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private fun open(method: String, url: String, pat: String) =
    (URL("https://api.github.com$url").openConnection() as HttpURLConnection).apply {
        requestMethod = method
        setRequestProperty("Authorization", "Bearer $pat")
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    }

/** Returns null on success, otherwise an error message. Blocking. */
fun validate(repo: String, pat: String): String? = try {
    when (val code = open("GET", "/repos/$repo", pat).responseCode) {
        200 -> null
        401 -> "Invalid token"
        404 -> "Repo not found or token has no access"
        else -> "GitHub returned HTTP $code"
    }
} catch (e: IOException) {
    "Network error: ${e.message}"
}

/** Creates [path] in [repo]. Throws on failure. 422 = file already exists = already pushed. Blocking. */
fun put(repo: String, pat: String, path: String, bytes: ByteArray) {
    val body = JSONObject()
        .put("message", "Add $path")
        .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        .toString().toByteArray()
    val conn = open("PUT", "/repos/$repo/contents/$path", pat)
    conn.doOutput = true
    conn.setFixedLengthStreamingMode(body.size)
    conn.outputStream.use { it.write(body) }
    val code = conn.responseCode
    if (code != 201 && code != 422) throw IOException("PUT $path: HTTP $code")
}
