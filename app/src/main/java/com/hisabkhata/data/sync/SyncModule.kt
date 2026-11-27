package com.hisabkhata.data.sync

import com.hisabkhata.data.cloud.CloudGatewayImpl
import com.hisabkhata.domain.sync.CloudGateway
import com.hisabkhata.domain.sync.SyncEngine
import com.hisabkhata.domain.sync.SyncRecordSource
import com.hisabkhata.domain.sync.SyncStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncBindings {
    @Binds @Singleton
    abstract fun store(impl: RoomSyncStore): SyncStore

    @Binds @Singleton
    abstract fun records(impl: RoomSyncRecords): SyncRecordSource

    @Binds @Singleton
    abstract fun gateway(impl: CloudGatewayImpl): CloudGateway
}

@Module
@InstallIn(SingletonComponent::class)
object SyncEngineModule {
    /**
     * One engine for the app. Retries are bounded so a disabled account or a broken
     * row cannot keep the app hammering the server; the user sees "Action needed".
     */
    @Provides
    @Singleton
    fun engine(store: SyncStore, records: SyncRecordSource, gateway: CloudGateway): SyncEngine =
        SyncEngine(store = store, records = records, gateway = gateway, maxAttemptsBeforePause = 8)
}
