package com.example.audio_app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.util.Locale

class DeviceDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var timeout: Runnable? = null
    private val reportedHosts = mutableSetOf<String>()

    fun start(
        onCandidate: (baseUrl: String) -> Unit,
        onFinished: () -> Unit,
    ): Boolean {
        stop()
        reportedHosts.clear()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.lowercase(Locale.ROOT).contains("_http._tcp")) return
                val host = serviceInfo.host?.hostAddress
                if (!host.isNullOrBlank()) {
                    report(host, serviceInfo.port, onCandidate)
                    return
                }
                runCatching {
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val address = resolved.host?.hostAddress
                                if (!address.isNullOrBlank()) {
                                    report(address, resolved.port, onCandidate)
                                }
                            }
                        },
                    )
                }.onFailure {
                    onFinished()
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(
                onFinished,
            )

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = finish(
                onFinished,
            )
        }
        discoveryListener = listener
        return runCatching {
            nsdManager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            timeout = Runnable {
                if (discoveryListener != null) {
                    stop()
                    onFinished()
                }
            }.also { handler.postDelayed(it, DISCOVERY_TIMEOUT_MS) }
            true
        }.onFailure {
            stop()
            onFinished()
        }.getOrDefault(false)
    }

    fun stop() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
    }

    private fun report(
        host: String,
        port: Int,
        onCandidate: (String) -> Unit,
    ) {
        val normalizedHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
        val key = "$normalizedHost:$port"
        if (reportedHosts.add(key)) {
            val portSuffix = if (port == 80) "" else ":$port"
            handler.post { onCandidate("http://$normalizedHost$portSuffix") }
        }
    }

    private fun finish(onFinished: () -> Unit) {
        stop()
        handler.post(onFinished)
    }

    private companion object {
        const val DISCOVERY_TIMEOUT_MS = 12_000L
    }
}
