plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)

    id("maven-publish")
    id("signing")
}

group = "app.nubrick"
version = libs.versions.nubrick.get()

android {
    namespace = "app.nubrick.nubrick"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "VERSION_NAME", "\"${project.version}\"")

        aarMetadata {
            minCompileSdk = libs.versions.androidMinSdk.get().toInt()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }
    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
    }
    publishing {
        singleVariant("release") {
//            withJavadocJar() // こっちだとsigningに間に合わないので諦めて空にする
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        optIn.add("app.nubrick.nubrick.FlutterBridgeApi")
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    api(platform(libs.compose.bom))
    implementation(libs.compose.ui.tooling)
    api(libs.compose.ui)
    implementation(libs.compose.foundation)
    api(libs.compose.runtime)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

tasks.register<Jar>("javadocEmptyJar") {
    archiveClassifier = "javadoc"
}
tasks.register<Zip>("makeArchive") {
    dependsOn("publishMavenPublicationToMavenRepository")
    from(layout.buildDirectory.dir("repos/app/nubrick/nubrick/$version"))
    into("app/nubrick/nubrick/$version")
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("maven") {
                groupId = project.group as String
                artifactId = "nubrick"
                version = project.version as String
                from(components["release"])
                artifact(tasks["javadocEmptyJar"])

                pom {
                    name = "Nubrick SDK"
                    description =
                        "Nubrick is a tool that helps you to build/manage your mobile application."
                    url = "https://github.com/plaidev/nubrick-android"
                    licenses {
                        license {
                            name = "The Apache License, Version 2.0"
                            url = "https://github.com/plaidev/nubrick-android/blob/main/LICENSE"
                            distribution = "repo"
                        }
                    }
                    developers {
                        developer {
                            id = "nubrick"
                            name = "nubrick"
                            email = "nubrick-support@plaid.co.jp"
                        }
                    }
                    scm {
                        connection = "scm:git:https://github.com/plaidev/nubrick-android.git"
                        developerConnection = "scm:git:ssh://github.com/plaidev/nubrick-android.git"
                        url = "https://github.com/plaidev/nubrick-android"
                    }
                }
            }
        }
        repositories {
            maven {
                url = uri(layout.buildDirectory.dir("repos"))
            }
        }
    }
    signing {
        val signingKey = System.getenv("GPG_SIGNING_KEY")
        val signingKeyPassphrase = System.getenv("GPG_SIGNING_KEY_PASSPHRASE")
        useInMemoryPgpKeys(signingKey, signingKeyPassphrase)
        sign(publishing.publications["maven"])
    }
}
