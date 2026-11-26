package com.jemcik.jemrec.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One mDNS look for adbd's Wireless debugging service through NsdManager:
 * every service found, resolved, and its port handed to [AdbdFinder] in
 * order. Unlike libadb's AdbMdns, which JemRec used until 1.9, it drops
 * nothing - AdbdFinder checks each port by adbd's own answer, which needs
 * neither libadb's host filter nor its bind test (both silent, and the bind
 * test never ran on an emulator: libadb binds 10.0.2.2 there, so an AVD
 * took a port a phone would have dropped) - and it is stopped even when
 * closed before its discovery started: NsdManager keeps the listener from
 * discoverServices on and sends its requests in order (android12-release),
 * where AdbMdns stopped only after its started callback.
 *
 * Resolves one at a time: NsdService turns a second resolve from the same
 * app away with FAILURE_ALREADY_ACTIVE (android12-release), and that one is
 * asked again shortly.
 */
internal class MdnsLook(context: Context, serviceType: String = SERVICE_TYPE) : AdbdFinder.Look {

    private val nsd: NsdManager? = context.applicationContext.getSystemService(NsdManager::class.java)
    private val ports = LinkedBlockingQueue<Int>()
    private val waiting = ArrayDeque<NsdServiceInfo>()
    private val lock = Any()
    private var resolving = false
    private var closed = false
    private var started = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "mdns: the look did not start ($errorCode)")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            synchronized(lock) {
                if (closed) return
                waiting.addLast(serviceInfo)
            }
            resolveNext()
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
    }

    init {
        val manager = nsd
        started = manager != null && runCatching {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discovery)
        }.onFailure { Log.w(TAG, "mdns: could not look - ${it.message}") }.isSuccess
    }

    override fun next(timeoutMs: Long): Int? = ports.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        synchronized(lock) {
            closed = true
            waiting.clear()
        }
        if (started) runCatching { nsd?.stopServiceDiscovery(discovery) }
    }

    private fun resolveNext() {
        val service = synchronized(lock) {
            if (closed || resolving) return
            val next = waiting.removeFirstOrNull() ?: return
            resolving = true
            next
        }
        val asked = runCatching {
            @Suppress("DEPRECATION") // resolveService: its replacement, registerServiceInfoCallback, is API 34
            nsd?.resolveService(service, Resolve(service))
        }.isSuccess
        if (!asked) done()
    }

    private fun done() {
        synchronized(lock) { resolving = false }
        resolveNext()
    }

    private inner class Resolve(private val service: NsdServiceInfo) : NsdManager.ResolveListener {
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.port > 0) ports.offer(serviceInfo.port)
            done()
        }

        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                synchronized(lock) {
                    resolving = false
                    if (!closed) waiting.addFirst(service)
                }
                Handler(Looper.getMainLooper()).postDelayed(::resolveNext, AGAIN_MS)
            } else {
                done()
            }
        }
    }

    companion object {
        private const val TAG = "JemRec"
        const val SERVICE_TYPE = "_adb-tls-connect._tcp"
        private const val AGAIN_MS = 200L
    }
}
