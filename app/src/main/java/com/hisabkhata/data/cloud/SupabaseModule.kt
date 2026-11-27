package com.hisabkhata.data.cloud

import com.hisabkhata.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import javax.inject.Singleton

/**
 * One HTTP client for the whole app.
 *
 * Only the publishable key is compiled in: it exposes the `anon`/`authenticated`
 * roles, and every table is owner-only under RLS. A secret/service-role key must
 * never be added here or anywhere else in the APK.
 *
 * Sessions are deliberately NOT persisted to disk. Sign-in is required at each
 * launch of v1.3.0 (the agreed choice), which also keeps another account's cached
 * rows from being usable on a shared phone after a restart.
 */
@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {
    @Provides
    @Singleton
    fun client(): SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    ) {
        install(Auth) {
            // Required by enough auth endpoints that we leave it at its default,
            // but the Compose screens always start from a signed-out state.
            alwaysAutoRefresh = false
        }
        install(Postgrest)
    }

    @Provides
    fun auth(client: SupabaseClient): Auth = client.auth

    @Provides
    fun postgrest(client: SupabaseClient): Postgrest = client.postgrest
}
