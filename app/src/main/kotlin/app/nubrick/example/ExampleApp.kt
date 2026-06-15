package app.nubrick.example

import android.app.Application
import app.nubrick.nubrick.Config
import app.nubrick.nubrick.NubrickSDK

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NubrickSDK.initialize(
            context = this,
            config = Config(projectId = "p_d357ola9io6g00f6evag")
        )
        NubrickSDK.setUserId("user-42")
        NubrickSDK.setUserProperties(mapOf(
            "name" to "Victor",
            "plan" to "pro",
            "locale" to "en",
        ))
    }
}
