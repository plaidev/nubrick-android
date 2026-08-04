package app.nubrick.example.javaxml;

import android.app.Application;
import android.util.Log;

import app.nubrick.nubrick.Config;
import app.nubrick.nubrick.NubrickSDK;

public final class ExampleJavaXmlApp extends Application {
    private static final String PROJECT_ID = "YOUR_PROJECT_ID";

    @Override
    public void onCreate() {
        super.onCreate();

        Config config = new Config(PROJECT_ID);
        if (!NubrickSDK.initialize(this, config)) {
            // Host can retry later or disable Nubrick-dependent UI.
            Log.w("ExampleJavaXmlApp", "NubrickSDK.initialize failed");
        }
    }
}
