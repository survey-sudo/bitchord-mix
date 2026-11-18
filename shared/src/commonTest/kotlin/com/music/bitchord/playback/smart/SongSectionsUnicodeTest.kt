package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals

class SongSectionsUnicodeTest {
    @Test
    fun lyricNormalizationKeepsMultilingualWordsAndRepetition() {
        assertEquals("ateez enhypen", normalizeLyric("ATEEZ ENHYPEN"))
        assertEquals("에이티즈 엔하이픈", normalizeLyric("에이티즈 엔하이픈"))
        assertEquals("meovv セツナ ハナビ", normalizeLyric("MEOVV セツナ・ハナビ"))
        assertEquals("café déjà vu", normalizeLyric("Café déjà-vu"))
        assertEquals("hello hello", normalizeLyric("Hello, hello!"))
        assertEquals("cafe\u0301", normalizeLyric("Cafe\u0301!"))
        assertEquals("مُوسِيقَى", normalizeLyric("مُوسِيقَى!"))
    }
}
