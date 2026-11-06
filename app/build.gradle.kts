import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 从 local.properties 读取 release 签名配置（该文件不随 git 分发）
val keystoreProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// 专属 release 密钥（路径相对 app 模块目录）；文件存在才启用专属签名，
// 开源用户缺失密钥时自动回退到 debug 签名，保证任何人 clone 后都能编译
val releaseKeystore = keystoreProperties.getProperty("RELEASE_STORE_FILE")?.let { file(it) }
val hasReleaseSigning = releaseKeystore?.exists() == true

// https://discuss.kotlinlang.org/t/use-git-hash-as-version-number-in-build-gradle-kts/19818/2
// this function will return the git version number, and will be used as versionCode
fun gitVersion(): Int {
    return try {
        val os = org.apache.commons.io.output.ByteArrayOutputStream()
        project.exec {
            commandLine = "git rev-list HEAD --count".split(" ")
            standardOutput = os
        }
        String(os.toByteArray()).trim().toInt()
    } catch (e: Exception) {
        1 // 无 git 仓库（如直接解压源码）时的兜底 versionCode
    }
}

android {
    namespace = "com.mandgamit.chess"
    compileSdk = 34
    ndkVersion = libs.versions.ndk.get()

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.mandgamit.chess"
        minSdk = 26
        targetSdk = 33
        versionCode = gitVersion()
        versionName = "1.2"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }

        // 好友对战服务器地址（local.properties 配 battle.server.url，不随 git 分发；缺省本地回环地址）
        buildConfigField(
            "String",
            "BATTLE_SERVER_URL",
            "\"${keystoreProperties.getProperty("battle.server.url", "wss://127.0.0.1:9001/battle")}\""
        )
    }

    packaging {
        resources.excludes.addAll(
            listOf("/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md")
        )
        jniLibs {
            useLegacyPackaging = true
        }
    }

    flavorDimensions += "pikafish"
    productFlavors {
        create("armv8-") {
            //flavor configurations here
            dimension = "pikafish"
            buildConfigField("String", "PIKAFISH_ENGINE_FILE", "\"libpikafish-armv8.so\"")
        }
        create("armv8-dotprod-") {
            //flavor configurations here
            dimension = "pikafish"
            buildConfigField("String", "PIKAFISH_ENGINE_FILE", "\"libpikafish-armv8-dotprod.so\"")
        }
    }

    // Release 签名为可选项：本机存在专属密钥（local.properties 配置）时使用之；
    // 开源环境无密钥则不创建该配置，release 包回退用 debug 密钥签名。
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                keyAlias = keystoreProperties.getProperty("RELEASE_KEY_ALIAS")
                storePassword = keystoreProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyPassword = keystoreProperties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig =
                if (hasReleaseSigning) signingConfigs["release"] else signingConfigs["debug"]
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "2g" }
        }
    }
    ndkVersion = "25.1.8937393"

    // https://gist.github.com/pankajXdev/574063901ada2fafa329068f41ddb076
    // Config your output file name in Gradle Kotlin DSL
    applicationVariants.all {
        outputs.all { output ->
            if (output is BaseVariantOutputImpl) {
                val date = SimpleDateFormat("yyyyMMdd").format(Date())
                val filename = "MandGamit_${date}_${versionCode}_${name}.apk"
                output.outputFileName = filename
            }
            true
        }
    }
}

// https://withme.skullzbones.com/blog/programming/execute-native-binaries-android-q-no-root/
tasks.register<Jar>("nativeLibsToJar") {
    description = "create a jar archive of the native libs"
    destinationDirectory.set(layout.buildDirectory.dir("native-libs"))
    archiveBaseName.set("native-libs")
    from(fileTree("src/main/pikafish/") {
        include("**/*")
    })
    into("lib/")
}

tasks.named("preBuild") {
    dependsOn(tasks.named("nativeLibsToJar"))
}

dependencies {
    // https://withme.skullzbones.com/blog/programming/execute-native-binaries-android-q-no-root/
    implementation(files("$buildDir/native-libs/native-libs.jar"))
    // YOLOv5 棋子检测推理（fp16 模型在 assets/yolov5s_xq_fp16.tflite）
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("com.readystatesoftware.sqliteasset:sqliteassethelper:+")
    // https://mvnrepository.com/artifact/com.igormaznitsa/jbbp
    implementation("com.igormaznitsa:jbbp:3.0.0")
    // https://github.com/PhilJay/MPAndroidChart
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation(project(":filepicker"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    // 识别功能：历史列表网格 + 后台识别协程
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.junit.jupiter)
}
