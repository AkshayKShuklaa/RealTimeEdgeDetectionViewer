package com.example.rtedge.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

class EdgeGlSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val edgeRenderer = EdgeRenderer()

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(edgeRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun renderer(): EdgeRenderer = edgeRenderer

    fun submitFrame(frame: java.nio.ByteBuffer, width: Int, height: Int) {
        edgeRenderer.submitFrame(frame, width, height)
        requestRender()
    }
}

