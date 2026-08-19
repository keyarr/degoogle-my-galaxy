import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use { load(it) }
    }
}

fun releaseSigningProperty(name: String, environmentName: String): String? =
    releaseSigningProperties.getProperty(name)
        ?: providers.environmentVariable(environmentName).orNull

val releaseStoreFile = releaseSigningProperty("storeFile", "DEGOOGLE_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningProperty("storePassword", "DEGOOGLE_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningProperty("keyAlias", "DEGOOGLE_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningProperty("keyPassword", "DEGOOGLE_RELEASE_KEY_PASSWORD")
val releaseSigningConfigured = !releaseStoreFile.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    rootProject.file(releaseStoreFile.orEmpty()).isFile

android {
    namespace = "dev.degoogle.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.degoogle"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.3.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            releaseStoreFile?.let { storeFile = rootProject.file(it) }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Mantém o script em assets sempre sincronizado com o script canônico na raiz
    val copyRootScript = tasks.register<Copy>("copyRootScript") {
        from(rootProject.file("degoogle.sh"))
        into(layout.projectDirectory.dir("src/main/assets/root"))
    }

    tasks.named("preBuild") {
        dependsOn(copyRootScript)
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) {
        check(releaseSigningConfigured) {
            "Release signing is not configured. Provide keystore.properties or DEGOOGLE_RELEASE_* environment variables."
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
