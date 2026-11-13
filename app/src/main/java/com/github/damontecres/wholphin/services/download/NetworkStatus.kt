package com.github.damontecres.wholphin.services.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import timber.log.Timber

/** Cheap "do we have a network at all" check, used to skip server calls that would only time out. */
object NetworkStatus {
    fun isOnline(context: Context): Boolean =
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (ex: Exception) {
            // Missing permission or odd OEM behaviour: assume online so nothing is blocked.
            Timber.w(ex, "Connectivity check failed; assuming online")
            true
        }
}
