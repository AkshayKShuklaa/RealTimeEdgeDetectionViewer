package com.example.rtedge

import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Native libraries are now loaded in the EdgeProcessor companion object
    }
}
