package app.nubrick.example

import android.app.Application
import android.util.Log
import app.nubrick.nubrick.Config
import app.nubrick.nubrick.NubrickSDK

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Override locally with nubrick.projectId=... in local.properties
        val initialized = NubrickSDK.initialize(
            context = this,
            config = Config(projectId = BuildConfig.NUBRICK_PROJECT_ID)
        )
        if (!initialized) {
            // Host can retry later or disable Nubrick-dependent UI.
            Log.w("ExampleApp", "NubrickSDK.initialize failed")
        }
    }
}
