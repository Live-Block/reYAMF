package com.mja.reyamf

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.mja.reyamf.manager.utils.AppContext
import dagger.hilt.android.HiltAndroidApp

lateinit var application: Application

@HiltAndroidApp
open class Reyamf: Application() {

    init {
        application = this
        AppContext.context = this
    }

    override fun onCreate() {
        super.onCreate()

        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}