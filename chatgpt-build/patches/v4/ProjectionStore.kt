package com.example.berserkoverlay

import android.graphics.Bitmap

object ProjectionStore {
    private val lock = Any()
    private var latest: Bitmap? = null
    private var frameGeneration: Long = 0L

    fun update(bitmap: Bitmap) = synchronized(lock) {
        latest?.recycle()
        latest = bitmap
        frameGeneration++
    }

    fun generation(): Long = synchronized(lock) { frameGeneration }

    fun copyLatest(): Bitmap? = synchronized(lock) {
        latest?.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun clear() = synchronized(lock) {
        latest?.recycle()
        latest = null
        frameGeneration++
    }
}
