package com.music.bitchord.data.stats

/**
 * Rewrites identifiers that name the official install when a backup of that
 * install is read by this fork.
 *
 * Only the application id moves. Class names stay `com.music.bitchord.…`
 * because the code's package did not change, and the TV id is left alone.
 * Notification channel ids in this app are short names (`bitchord_playback`,
 * `downloads`) and never carried the package, so there is nothing to rewrite
 * for them.
 */
object PackageRemap {
    const val OFFICIAL = "com.music.bitchord"
    const val FORK = "com.music.bitchord.loopfix"

    /** A directory in a filesystem path: `/data/user/0/com.music.bitchord/…`. */
    private val pathPackage = Regex("""(?<=/)com\.music\.bitchord(?=/|$)""")

    /** A content-URI authority, including `com.music.bitchord.fileprovider`. */
    private val authority = Regex("""(?<=://)com\.music\.bitchord(?=\.fileprovider|[/:?#]|$)""")

    /** The package half of a flattened component name, before the class. */
    private val componentPackage = Regex("""(?<![.\w])com\.music\.bitchord(?=/)""")

    fun remap(value: String): String = value
        .replace(pathPackage, FORK)
        // The `.fileprovider` suffix is a lookahead, so it stays put and the
        // authority becomes this fork's `….loopfix.fileprovider`.
        .replace(authority, FORK)
        .replace(componentPackage, FORK)

    fun remapValue(value: Any?): Any? = when (value) {
        is String -> remap(value)
        is Set<*> -> value.map { if (it is String) remap(it) else it }.toSet()
        else -> value
    }
}
