package com.example.rtedge.nativebridge

import java.io.Closeable
import java.nio.ByteBuffer

class EdgeProcessor : Closeable {

    private var nativeHandle: Long = nativeCreate()

    fun processFrame(
        nv21: ByteArray,
        width: Int,
        height: Int,
        outputBuffer: ByteBuffer,
        edgesOnly: Boolean
    ): Float {
        val handle = nativeHandle
        check(handle != 0L) { "EdgeProcessor not initialized." }
        return nativeProcess(handle, nv21, width, height, outputBuffer, edgesOnly)
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeProcess(
        handle: Long,
        nv21: ByteArray,
        width: Int,
        height: Int,
        outputBuffer: ByteBuffer,
        edgesOnly: Boolean
    ): Float

    companion object {
        init {
            System.loadLibrary("c++_shared")
            System.loadLibrary("opencv_java4")
            System.loadLibrary("edge_processor")
        }
    }
}

