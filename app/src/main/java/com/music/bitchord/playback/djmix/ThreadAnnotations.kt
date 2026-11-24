package com.music.bitchord.playback.djmix

/**
 * Documentation-level thread contracts for the djmix / loop-mix path (B13).
 *
 * These are `SOURCE` retention markers: they cost nothing at runtime and exist
 * so a reader (and a grep-based audit) can see which code runs on the realtime
 * audio thread and which must stay off it. They do not enforce anything by
 * themselves; enforcement is the review and the audit script.
 */

/**
 * Runs on the realtime audio thread (the sink's render callback or a function it
 * calls directly). Rules: no heap allocation (`FloatArray(`, `ArrayList(`,
 * `listOf(`, boxing), no logging (`Log.`), no locks that another thread may hold
 * for long, no I/O, no JNI calls that allocate on the Java side.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AudioThread

/**
 * Runs on a background preparation thread (decode, stem split, script build,
 * native deck upload, analysis). Allocation and blocking are allowed. Must not be
 * called from an [AudioThread] function.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class PrepThread

/**
 * Runs on the main / controller thread (UI state, lifecycle, flows the UI
 * collects). Must not block on the audio thread or on [PrepThread] work.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class MainThread
