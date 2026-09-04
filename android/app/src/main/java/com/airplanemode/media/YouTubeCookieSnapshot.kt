package com.airplanemode.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object YouTubeCookieSnapshot {
  fun create(context: Context, mediaItemId: String): File? {
    val headers = readHeaders() ?: return null
    val values = linkedMapOf<String, String>()
    headers.forEach { header ->
      header.split(';').forEach { pair ->
        val separator = pair.indexOf('=')
        if (separator <= 0) return@forEach
        val name = pair.substring(0, separator).trim()
        val value = pair.substring(separator + 1).trim()
        if (name.isNotEmpty() && value.isNotEmpty()) values[name] = value
      }
    }
    if (values.isEmpty()) return null

    val directory = File(context.cacheDir, "yt-runtime").apply { mkdirs() }
    val temporary = File(directory, "cookies-$mediaItemId.part")
    val target = File(directory, "cookies-$mediaItemId.txt")
    temporary.bufferedWriter().use { output ->
      output.appendLine("# Netscape HTTP Cookie File")
      values.forEach { (name, value) ->
        output.appendLine(".youtube.com\tTRUE\t/\tTRUE\t0\t$name\t$value")
      }
    }
    temporary.setReadable(false, false)
    temporary.setWritable(false, false)
    temporary.setReadable(true, true)
    temporary.setWritable(true, true)
    if (target.exists() && !target.delete()) {
      temporary.delete()
      return null
    }
    if (!temporary.renameTo(target)) {
      temporary.delete()
      return null
    }
    return target
  }

  private fun readHeaders(): List<String>? {
    var result: List<String>? = null
    val read = {
      val manager = CookieManager.getInstance()
      result = listOfNotNull(
        manager.getCookie("https://music.youtube.com/"),
        manager.getCookie("https://www.youtube.com/"),
      )
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
      read()
    } else {
      val latch = CountDownLatch(1)
      Handler(Looper.getMainLooper()).post {
        try { read() } finally { latch.countDown() }
      }
      if (!latch.await(5, TimeUnit.SECONDS)) return null
    }
    return result
  }
}
