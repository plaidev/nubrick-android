package app.nubrick.example

import android.app.Application
import android.util.Log
import app.nubrick.nubrick.Config
import app.nubrick.nubrick.NubrickSDK

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val initialized = NubrickSDK.initialize(
            context = this,
            config = Config(projectId = "cgv3p3223akg00fod19g")
        )
        if (!initialized) {
            // Host can retry later or disable Nubrick-dependent UI.
            Log.w("ExampleApp", "NubrickSDK.initialize failed")
        }
    }
}
