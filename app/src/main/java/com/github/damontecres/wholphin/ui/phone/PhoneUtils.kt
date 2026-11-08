package com.github.damontecres.wholphin.ui.phone

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.damontecres.wholphin.services.download.DownloadQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** True on a phone/tablet (no Android TV leanback feature). Phone-only UI is gated on this. */
fun Context.isPhoneDevice(): Boolean = !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

@Composable
fun rememberIsPhone(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.isPhoneDevice() }
}

/** Dark material3 palette used by the phone screens (the rest of the app is tv-material). */
val PhoneColorScheme: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF8AB4F8),
        onPrimary = Color(0xFF0B1A33),
        secondary = Color(0xFFB0BEC5),
        surface = Color(0xFF14161A),
        onSurface = Color(0xFFECEFF1),
        surfaceVariant = Color(0xFF23262C),
        onSurfaceVariant = Color(0xFFB8BEC6),
        background = Color(0xFF0E0F12),
        onBackground = Color(0xFFECEFF1),
        error = Color(0xFFFF8A80),
    )

private val Context.downloadPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "download_prefs")

/** Remembers the last quality tier chosen in the download picker. */
object DownloadPrefs {
    private val QUALITY = stringPreferencesKey("quality")

    fun quality(context: Context): Flow<DownloadQuality> =
        context.downloadPrefsDataStore.data.map { DownloadQuality.fromParam(it[QUALITY]) }

    suspend fun setQuality(
        context: Context,
        quality: DownloadQuality,
    ) {
        context.downloadPrefsDataStore.edit { it[QUALITY] = quality.param }
    }
}
