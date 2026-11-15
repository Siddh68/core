package com.maxrave.data.di.loader

import com.maxrave.domain.data.model.librarysync.LibrarySyncState
import com.maxrave.domain.data.model.librarysync.PendingSettings
import com.maxrave.domain.repository.LibrarySyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

// The iOS player arrives in Phase 2; until then the no-op handler from createMediaServiceHandler is all
// there is, and it needs no module of its own.
actual fun loadMediaService() {
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
