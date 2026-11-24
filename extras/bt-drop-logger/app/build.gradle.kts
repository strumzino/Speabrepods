import java.util.Properties

plugins {
    id("com.android.application")
}

val props = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
val hasSigning = listOf("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
    .all { props[it]?.toString()?.isNotBlank() == true }

android {
    namespace = "dev.btdroplogger"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.btdroplogger"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(props["RELEASE_STORE_FILE"] as String)
                storePassword = props["RELEASE_STORE_PASSWORD"] as String
                keyAlias = props["RELEASE_KEY_ALIAS"] as String
                keyPassword = props["RELEASE_KEY_PASSWORD"] as String
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
