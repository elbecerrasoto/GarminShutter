package com.remoteshutter

import android.app.Application

/**
 * Single place that owns the Connect IQ link, so the link survives
 * configuration changes and is created once per process.
 */
class App : Application() {

    lateinit var ciq: CiqLink
        private set

    override fun onCreate() {
        super.onCreate()
        ciq = CiqLink(this)
    }
}
