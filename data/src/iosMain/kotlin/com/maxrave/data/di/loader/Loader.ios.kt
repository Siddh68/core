package com.maxrave.data.di.loader

import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.domain.data.model.librarysync.LibrarySyncState
import com.maxrave.domain.data.model.librarysync.PendingSettings
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.LibrarySyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module

// The service scope is what createMediaServiceHandler hands to IosMediaPlayerHandlerImpl (and through
// it to AvPlayerAdapter, which derives its own serial context from it). Unlike Desktop, no
// MediaPlayerInterface is bound: the iOS handler builds its AvPlayerAdapter itself from
// NativePlayerRegistry.engine and makes no Koin lookups. Shared code still resolves the player
// cache and the download handler; those stay stand-ins that hold nothing until Phase 3.5.
actual fun loadMediaService() {
    loadKoinModules(
        module {
            single<CoroutineScope>(qualifier = named(SERVICE_SCOPE)) {
                // One writer at a time, like Desktop's single player thread.
                CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob())
            }
            single<CacheRepository> { EmptyCacheRepository }
            single<DownloadHandler> { NoOpDownloadHandler }
        },
    )
}

private object EmptyCacheRepository : CacheRepository {
    override suspend fun getCacheSize(cacheName: String): Long = 0L

    override fun clearCache(cacheName: String) = Unit

    override suspend fun getAllCacheKeys(cacheName: String): List<String> = emptyList()

    override suspend fun getFullyCachedSongIds(): List<String> = emptyList()

    override suspend fun removeCachedSong(videoId: String) = Unit
}

private object NoOpDownloadHandler : DownloadHandler {
    override suspend fun downloadTrack(
        videoId: String,
        title: String,
        thumbnail: String,
    ) = Unit

    override fun removeDownload(videoId: String) = Unit

    override fun removeAllDownloads() = Unit

    override val downloads: StateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>> =
        MutableStateFlow(emptyMap())

    override val downloadTask: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())
}

// iOS takes no part in login or library sync. Shared screens (Settings) still inject
// LibrarySyncRepository, so bind one that is never paired and does nothing.
actual fun loadLoginSyncModule() {
    loadKoinModules(
        module {
            single<LibrarySyncRepository> { UnsupportedLibrarySyncRepository }
        },
    )
}

private object UnsupportedLibrarySyncRepository : LibrarySyncRepository {
    override val state: StateFlow<LibrarySyncState> = MutableStateFlow(LibrarySyncState())
    override val pendingSettings: StateFlow<PendingSettings?> = MutableStateFlow(null)

    override suspend fun syncNow() = Unit

    override suspend fun unpairAll() = Unit

    override suspend fun applyPendingSettings() = Unit

    override suspend fun dismissPendingSettings() = Unit
}
