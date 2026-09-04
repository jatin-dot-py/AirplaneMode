package com.airplanemode.doomscroll

import com.airplanemode.doomscroll.data.RemoteMediaCandidate
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object ReelUrlPolicy {
  private val allowedDomains = listOf(
    "cdninstagram.com",
    "fbcdn.net",
    "fbsbx.com",
  )

  fun isAllowedHttpsUrl(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    return try {
      val uri = URI(value)
      val host = uri.host?.lowercase() ?: return false
      uri.scheme.equals("https", ignoreCase = true) && allowedDomains.any { domain ->
        host == domain || host.endsWith(".$domain")
      }
    } catch (_: Exception) {
      false
    }
  }

  fun ordered(candidates: List<RemoteMediaCandidate>): List<RemoteMediaCandidate> = candidates
    .asSequence()
    .filter { isAllowedHttpsUrl(it.url) }
    .distinctBy { it.url }
    .sortedWith(
      compareByDescending<RemoteMediaCandidate> { it.width.toLong() * it.height.toLong() }
        .thenByDescending { it.width }
        .thenByDescending { it.height },
    )
    .take(MAX_CANDIDATES)
    .toList()

  fun toJson(candidates: List<RemoteMediaCandidate>): String {
    val array = JSONArray()
    sanitizedInOrder(candidates).forEach { candidate ->
      array.put(
        JSONObject()
          .put("url", candidate.url)
          .put("width", candidate.width)
          .put("height", candidate.height),
      )
    }
    return array.toString()
  }

  fun fromJson(value: String): List<RemoteMediaCandidate> = try {
    val array = JSONArray(value)
    buildList {
      for (index in 0 until array.length()) {
        val candidate = array.optJSONObject(index) ?: continue
        val url = candidate.optString("url")
        if (!isAllowedHttpsUrl(url)) continue
        add(
          RemoteMediaCandidate(
            url = url,
            width = candidate.optInt("width", 0).coerceAtLeast(0),
            height = candidate.optInt("height", 0).coerceAtLeast(0),
          ),
        )
      }
    }.let(::sanitizedInOrder)
  } catch (_: Exception) {
    emptyList()
  }

  private fun sanitizedInOrder(
    candidates: List<RemoteMediaCandidate>,
  ): List<RemoteMediaCandidate> = candidates
    .asSequence()
    .filter { isAllowedHttpsUrl(it.url) }
    .distinctBy(RemoteMediaCandidate::url)
    .take(MAX_CANDIDATES)
    .toList()

  const val MAX_CANDIDATES = 6
}
