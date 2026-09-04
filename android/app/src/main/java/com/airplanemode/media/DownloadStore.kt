package com.airplanemode.media

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DownloadManager
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executors

@androidx.annotation.OptIn(UnstableApi::class)
object DownloadStore {
  @Volatile private var databaseProvider: StandaloneDatabaseProvider? = null
  @Volatile private var cache: SimpleCache? = null
  @Volatile private var manager: DownloadManager? = null
  private val httpClient by lazy { OkHttpClient.Builder().build() }

  private fun database(context: Context): StandaloneDatabaseProvider =
    databaseProvider ?: synchronized(this) {
      databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext)
        .also { databaseProvider = it }
    }

  fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
    cache ?: SimpleCache(
      File(context.filesDir, "media3-downloads"),
      NoOpCacheEvictor(),
      database(context),
    ).also { cache = it }
  }

  fun upstreamFactory(context: Context) = DefaultDataSource.Factory(
    context,
    OkHttpDataSource.Factory(httpClient),
  )

  fun playbackFactory(context: Context): CacheDataSource.Factory = CacheDataSource.Factory()
    .setCache(cache(context))
    .setUpstreamDataSourceFactory(upstreamFactory(context))
    .setCacheWriteDataSinkFactory(null)

  fun downloadManager(context: Context): DownloadManager = manager ?: synchronized(this) {
    manager ?: DownloadManager(
      context,
      database(context),
      cache(context),
      OkHttpDataSource.Factory(httpClient),
      Executors.newFixedThreadPool(3),
    ).also { manager = it }
  }
}
