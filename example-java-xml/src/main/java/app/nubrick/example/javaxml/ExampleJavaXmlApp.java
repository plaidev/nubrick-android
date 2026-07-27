package app.nubrick.example.javaxml;

import android.app.Application;

import app.nubrick.nubrick.Config;
import app.nubrick.nubrick.NubrickSDK;

public final class ExampleJavaXmlApp extends Application {
    private static final String PROJECT_ID = "YOUR_PROJECT_ID";

    @Override
    public void onCreate() {
        super.onCreate();

        Config config = new Config(PROJECT_ID);
        NubrickSDK.initialize(this, config);
    }
}
