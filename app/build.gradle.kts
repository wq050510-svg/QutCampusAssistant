import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "cn.edu.qut.campus"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.edu.qut.campus"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.3.2"

        // 只保留中英文资源：AndroidX / Material / 旧版 Glance 会带进 80 种语言，
        // 裁剪后 resources.arsc 明显变小（实测 APK 内语言数从 80 → 2）
        resourceConfigurations += listOf("zh", "en")
    }

    // ------------------------------------------------------------------
    // 签名配置
    // 优先级：根目录 keystore.properties（正式密钥） > 本机 debug 密钥
    // 回退到 debug 密钥是为了兼容：v1.2.0 就是用 debug 密钥签名的，
    // 用同一个密钥签名才能让老用户「覆盖安装」而不是「先卸载」。
    // ------------------------------------------------------------------
    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val props = Properties().apply { propsFile.inputStream().use { load(it) } }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            } else {
                storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                logger.lifecycle(
                    "[signing] 未找到 keystore.properties，release 使用 debug 密钥签名（可覆盖安装历史版本）"
                )
            }
            // minSdk 26 不需要 v1(JAR) 签名；v2 + v3 兼顾兼容性与密钥轮换
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            // 体积与性能的核心开关：代码裁剪 + 资源裁剪
            // 实测收益：material-icons-extended 一次引入 11,398 个图标类（占主 dex 类数 63.4%），
            // 未使用的会被 R8 全部删除，另有 Glance/WorkManager 等零引用库一同消失
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // 调试包不混淆，便于断点与堆栈定位；独立包名可与正式包共存
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // 去掉 Kotlin 生成的参数/调用/接收者 null 断言，减小 dex 并减少无谓分支
        freeCompilerArgs += listOf(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "DebugProbesKt.bin",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.version",
                "META-INF/com/android/build/gradle/**"
            )
        }
    }

    // 关闭 APK 内的依赖 Blob（约几十 KB 且对用户无用）
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // collectAsStateWithLifecycle：后台时停止订阅，省电且避免无谓重组
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    // 保留 extended：工程用到 35 个图标，其中约 25 个不在 icons-core 里。
    // R8 会把 11,398 个未使用图标类全部裁掉，因此它对最终体积几乎无影响，
    // 收益主要在编译期（不再编译上万类）；换成自绘矢量图标收益很低、风险不低。
    implementation(libs.androidx.material.icons.extended)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Network & JSON
    implementation(libs.okhttp)
    implementation(libs.gson)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
    // @Preview 注解只在开发期需要，release 不含
    debugImplementation(libs.androidx.ui.tooling.preview)
}
