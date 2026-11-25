package com.music.bitchord.playback.djmix.store

import android.content.Context
import android.content.ContextWrapper
import com.music.bitchord.playback.smart.TrackAnalysis
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackStoreBridgeOwnershipTest {
    @Test
    fun injectedOwnerStoreIsReusedAcrossBridgesAndAccesses() {
        val ownerStore = identityOnlyStore()
        val calls = AtomicInteger()
        val provider = { calls.incrementAndGet(); ownerStore }
        val first = bridge(storeProvider = provider)
        val second = bridge(storeProvider = provider)

        assertEquals(0, calls.get())
        onWorker {
            repeat(2) {
                assertSame(ownerStore, first.djmixStore)
                assertSame(ownerStore, second.djmixStore)
            }
        }
        assertEquals(2, calls.get()) // Once per bridge, always the owner's instance.
    }

    @Test
    fun providerResolutionWaitsForFirstWorkerAccess() {
        val context = CountingContext()
        val constructingThread = Thread.currentThread()
        val providerThread = AtomicReference<Thread>()
        val availableStore = AtomicReference<DjmixAnalysisStore?>()
        val calls = AtomicInteger()
        val bridge = bridge(context) {
            calls.incrementAndGet()
            providerThread.set(Thread.currentThread())
            availableStore.get()
        }

        assertEquals(0, calls.get())
        assertEquals(0, context.applicationContextReads.get())
        val ownerStore = identityOnlyStore()
        availableStore.set(ownerStore)
        onWorker { assertSame(ownerStore, bridge.djmixStore) }
        assertEquals(1, calls.get())
        assertTrue(providerThread.get() !== constructingThread)
        assertEquals(0, context.applicationContextReads.get())
    }

    @Test
    fun missingProviderFailsClosedWithoutDatabaseFallback() {
        assertUnavailable { null }
    }

    @Test
    fun throwingProviderFailsClosedWithoutDatabaseFallback() {
        assertUnavailable { error("process owner unavailable") }
    }

    @Test
    fun legacySixArgumentConstructorDefersMissingOwnerLookup() {
        val context = CountingContext()
        val bridge = TrackStoreBridge(
            context, Executor { it.run() }, ConcurrentHashMap(),
            mutableSetOf(), mutableSetOf(), {},
        )

        assertEquals(0, context.applicationContextReads.get())
        onWorker {
            assertNull(bridge.djmixStore)
            assertNull(bridge.djmixStore)
        }
        assertEquals(1, context.applicationContextReads.get())
    }

    @Test
    fun legacyPositionalContentHashConstructorRemainsCompatible() {
        val context = CountingContext()
        val hashCalls = AtomicInteger()
        val bridge = TrackStoreBridge(
            context, Executor { it.run() }, ConcurrentHashMap(),
            mutableSetOf(), mutableSetOf(), {},
            { hashCalls.incrementAndGet(); "source-hash" },
        )

        assertEquals(0, context.applicationContextReads.get())
        onWorker { assertFalse(bridge.restoreFromDjmixStore("track")) }
        assertEquals(1, context.applicationContextReads.get())
        assertEquals(0, hashCalls.get())
    }

    private fun assertUnavailable(provider: () -> DjmixAnalysisStore?) {
        val context = CountingContext()
        val calls = AtomicInteger()
        val legacy = TrackAnalysis(trackId = "track")
        val results = ConcurrentHashMap<String, TrackAnalysis>().apply { put("track", legacy) }
        val provisional = mutableSetOf("track")
        val restoreAttempted = mutableSetOf<String>()
        val migrations = mutableListOf<Runnable>()
        val updates = AtomicInteger()
        val hashCalls = AtomicInteger()
        val bridge = TrackStoreBridge(
            context, Executor { migrations.add(it) }, results, provisional,
            restoreAttempted, { updates.incrementAndGet() },
            contentHashForTrack = { hashCalls.incrementAndGet(); "source-hash" },
            storeProvider = { calls.incrementAndGet(); provider() },
        )

        assertEquals(0, calls.get())
        onWorker {
            assertNull(bridge.djmixStore)
            assertFalse(bridge.restoreFromDjmixStore("track"))
            bridge.persistToDjmixStore("track", legacy)
            bridge.triggerDjmixMigrationOnce()
            assertEquals(1, migrations.size)
            migrations.single().run()
            assertNull(bridge.djmixStore)
        }
        assertEquals(1, calls.get())
        assertEquals(0, hashCalls.get())
        // DjmixDatabase construction reads applicationContext; zero rules out that fallback.
        assertEquals(0, context.applicationContextReads.get())
        assertSame(legacy, results["track"])
        assertEquals(setOf("track"), provisional)
        assertTrue(restoreAttempted.isEmpty())
        assertEquals(0, updates.get())
    }

    private fun bridge(
        context: Context = CountingContext(),
        storeProvider: () -> DjmixAnalysisStore?,
    ) = TrackStoreBridge(
        context, Executor { it.run() }, ConcurrentHashMap(),
        mutableSetOf(), mutableSetOf(), {}, storeProvider = storeProvider,
    )

    private fun identityOnlyStore(): DjmixAnalysisStore {
        // app's mockable Android return-default configuration permits helper construction.
        // Identity fixture only: never opens/queries SQLite or claims persistence coverage.
        return DjmixAnalysisStore(DjmixDatabase(CountingContext()))
    }

    private class CountingContext : ContextWrapper(null) {
        val applicationContextReads = AtomicInteger()

        override fun getApplicationContext(): Context {
            applicationContextReads.incrementAndGet()
            return this
        }
    }

    private fun <T> onWorker(block: () -> T): T {
        val task = FutureTask<T> { block() }
        Thread(task, "store-owner-test").apply { isDaemon = true }.start()
        return task.get(5, TimeUnit.SECONDS)
    }
}
