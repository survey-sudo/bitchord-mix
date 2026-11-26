package com.music.bitchord.playback.djmix.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.music.bitchord.data.Http
import com.music.bitchord.playback.smart.AnalysisLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One optional release attempt per process owner; no timer, polling, or retry loop. */
internal class ReleaseFlagsUpdater(context: Context, private val flags: DjmixFlags) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val started = AtomicBoolean(false)

    suspend fun refresh() {
        if (!started.compareAndSet(false, true)) return
        try {
            val connectivity = manager ?: throw IOException("Connectivity service unavailable")
            val network = awaitWifi(connectivity)
            val jobContext = currentCoroutineContext()
            // Match AutomixModels: both DNS and sockets stay on the checked network.
            val client = Http.client.newBuilder()
                .socketFactory(network.socketFactory)
                .dns(Dns { host -> network.getAllByName(host).toList() })
                .retryOnConnectionFailure(false)
                .followSslRedirects(false)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
            val bytes = fetch(client, network, "flags.json", DjmixFlags.MAX_RELEASE_BYTES)
            val checksum = fetch(client, network, DjmixFlags.SHA_SUFFIX, DjmixFlags.MAX_CHECKSUM_BYTES)
            flags.publishRelease(bytes, checksum) {
                jobContext.ensureActive()
                ensureWifi(network)
            }
        } catch (cancelled: CancellationException) {
            AnalysisLog.warn("djmix flags: release refresh cancelled; retaining verified cache")
            throw cancelled
        } catch (failure: Exception) {
            AnalysisLog.warn("djmix flags: release refresh unavailable; retaining verified cache", failure)
        }
    }

    private fun checkedWifi(): Network? {
        val connectivity = manager ?: return null
        val network = connectivity.activeNetwork ?: return null
        val caps = connectivity.getNetworkCapabilities(network) ?: return null
        return network.takeIf {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    private fun ensureWifi(network: Network) {
        if (checkedWifi() != network) throw IOException("Validated Wi-Fi unavailable or changed")
    }

    private suspend fun awaitWifi(connectivity: ConnectivityManager): Network {
        val ready = CompletableDeferred<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun changed() {
                try {
                    checkedWifi()?.let { ready.complete(it) }
                } catch (failure: Exception) {
                    ready.completeExceptionally(failure)
                }
            }
            override fun onAvailable(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
        }
        try {
            connectivity.registerDefaultNetworkCallback(callback)
            checkedWifi()?.let { ready.complete(it) }
            return ready.await()
        } finally {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    private suspend fun fetch(client: OkHttpClient, network: Network, asset: String, limit: Int): ByteArray {
        currentCoroutineContext().ensureActive()
        ensureWifi(network)
        val request = Request.Builder().url(RELEASE_URL + asset)
            .header("Accept-Encoding", "identity").build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (response.code != 200) throw IOException("HTTP ${response.code} for $asset")
                            val encoding = response.header("Content-Encoding")
                            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                                throw IOException("Unsupported flags content encoding")
                            }
                            val body = response.body ?: throw IOException("Missing $asset body")
                            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                            if (charset != Charsets.UTF_8) throw IOException("Flags response must be UTF-8")
                            if (body.contentLength() > limit) throw IOException("Oversized $asset")
                            DjmixFlags.readBounded(body.byteStream(), limit) {
                                if (!continuation.isActive) throw IOException("Flags request cancelled")
                                ensureWifi(network)
                            }
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }
    }

    private companion object {
        const val RELEASE_URL = "https://github.com/survey-sudo/bitchord-mix/releases/download/models-v1/"
    }
}
