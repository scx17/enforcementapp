package com.hdcollection.enforcement.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import timber.log.Timber

class NetworkMonitor(private val cm: ConnectivityManager) {

    val isOnline: Boolean
        get() {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    fun registerCallback(onAvailable: () -> Unit, onLost: () -> Unit) {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Timber.i("Network available")
                onAvailable()
            }

            override fun onLost(network: Network) {
                Timber.i("Network lost")
                onLost()
            }
        })
    }
}
