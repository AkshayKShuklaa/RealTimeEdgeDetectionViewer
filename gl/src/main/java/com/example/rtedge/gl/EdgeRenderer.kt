package com.example.rtedge.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class EdgeRenderer : GLSurfaceView.Renderer {

    private val vertexData = floatArrayOf(
        -1f, -1f, 0f, 1f,
        1f, -1f, 1f, 1f,
        -1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f
    )

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(vertexData.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(vertexData) }

    private val lock = ReentrantLock()
    private var frameBuffer: ByteBuffer? = null
    private var uploadBuffer: ByteBuffer? = null
    private var frameDirty = false
    private var frameWidth = 0
    private var frameHeight = 0
    private var textureId = 0
    private var program = 0
    private val projectionMatrix = FloatArray(16)
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    override fun onSurfaceCreated(unused: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        textureId = createTexture()
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        Matrix.setIdentityM(projectionMatrix, 0)
    }

    override fun onSurfaceChanged(unused: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        updateProjectionMatrix()
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(unused: javax.microedition.khronos.opengles.GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val frameData = lock.withLock {
            if (!frameDirty || frameBuffer == null) {
                return@withLock null
            }
            val src = frameBuffer!!
            if (uploadBuffer == null || uploadBuffer?.capacity() != src.capacity()) {
                uploadBuffer = ByteBuffer.allocateDirect(src.capacity()).order(ByteOrder.nativeOrder())
            }
            val target = uploadBuffer!!
            src.rewind()
            target.rewind()
            target.put(src)
            target.rewind()
            frameDirty = false
            target
        } ?: return

        frameData.rewind()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            frameWidth,
            frameHeight,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            frameData
        )

        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val matrixHandle = GLES20.glGetUniformLocation(program, "uMatrix")

        vertexBuffer.rewind()
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 4 * Float.SIZE_BYTES, vertexBuffer)

        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 4 * Float.SIZE_BYTES, vertexBuffer)

        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, projectionMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    fun submitFrame(buffer: ByteBuffer, width: Int, height: Int) {
        lock.withLock {
            if (frameBuffer == null || frameBuffer?.capacity() != buffer.capacity()) {
                frameBuffer = ByteBuffer.allocateDirect(buffer.capacity()).order(ByteOrder.nativeOrder())
            }
            frameBuffer?.let {
                buffer.rewind()
                it.rewind()
                it.put(buffer)
                it.rewind()
            }
            frameWidth = width
            frameHeight = height
            frameDirty = true
            updateProjectionMatrix()
        }
    }

    private fun updateProjectionMatrix() {
        val sWidth = surfaceWidth
        val sHeight = surfaceHeight
        if (sWidth == 0 || sHeight == 0 || frameWidth == 0 || frameHeight == 0) {
            Matrix.setIdentityM(projectionMatrix, 0)
            return
        }
        val viewAspect = sWidth.toFloat() / sHeight.toFloat()
        val frameAspect = frameWidth.toFloat() / frameHeight.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (frameAspect > viewAspect) {
            scaleX = 1f
            scaleY = frameAspect / viewAspect
        } else {
            scaleX = viewAspect / frameAspect
            scaleY = 1f
        }
        Matrix.setIdentityM(projectionMatrix, 0)
        Matrix.scaleM(projectionMatrix, 0, scaleX, scaleY, 1f)
    }

    private fun createTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val info = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("Failed to link program: $info")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Failed to compile shader: $info")
        }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            uniform mat4 uMatrix;
            void main() {
                gl_Position = uMatrix * aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}

