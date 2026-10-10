package com.ledgerx.data.sync

import android.content.Context
import android.net.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

class ConnectivityObserver(context: Context) {
    private val manager=context.getSystemService(ConnectivityManager::class.java)
    val connected: Flow<Boolean> = callbackFlow {
        fun publish() { trySend(manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true) }
        val callback=object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { publish() }
            override fun onLost(network: Network) { publish() }
            override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) { publish() }
        }
        manager.registerDefaultNetworkCallback(callback); publish()
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
