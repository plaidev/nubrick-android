package app.nubrick.example

import android.app.Application
import app.nubrick.nubrick.Config
import app.nubrick.nubrick.NubrickSDK

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NubrickSDK.initialize(
            context = this,
            config = Config(projectId = "cgv3p3223akg00fod19g")
        )
    }
}
