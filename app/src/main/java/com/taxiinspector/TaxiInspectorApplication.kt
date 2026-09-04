package com.taxiinspector

import android.app.Application
import com.taxiinspector.data.rides.AppContainer

class TaxiInspectorApplication : Application() {
    val appContainer: AppContainer by lazy { AppContainer(applicationContext) }
}
