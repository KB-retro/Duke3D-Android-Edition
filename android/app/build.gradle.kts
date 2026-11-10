plugins {
    id("com.android.application")
}

/** Lee un ajuste de -P o de una variable de entorno. */
fun config(name: String, default: String = ""): String =
    listOf(project.findProperty(name) as String?, System.getenv(name))
        .firstOrNull { !it.isNullOrBlank() }?.trim() ?: default

// Código de SDL2: lo descarga el workflow (o scripts/descargar-sdl.sh) en android/SDL2.
val sdlDir = rootProject.file("SDL2")

android {
    namespace = "com.kbretro.duke3d"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.kbretro.duke3d"
        minSdk = 26
        targetSdk = 35
        versionCode = config("VERSION_CODE", "1").toInt()
        versionName = "0.1.${config("VERSION_CODE", "0")}"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DSDL2_DIR=${sdlDir.absolutePath}", "-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets["main"].java.srcDir(File(sdlDir, "android-project/app/src/main/java"))

    // Clave fija si está en los secretos; si no, la de depuración (sirve para probar).
    val keystorePath = config("SIGNING_KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath.isNotBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = config("SIGNING_STORE_PASSWORD")
                keyAlias = config("SIGNING_KEY_ALIAS", "duke3d")
                keyPassword = config("SIGNING_KEY_PASSWORD", config("SIGNING_STORE_PASSWORD"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}
