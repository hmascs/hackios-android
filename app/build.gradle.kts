plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val gpuTest = providers.gradleProperty("gpuTest").orNull == "true"

android {
    if (gpuTest) {
        val gpuAssets = providers.gradleProperty("gpuAssetsDir")
            .map { file(it) }
            .getOrElse(layout.projectDirectory.dir("src/gpuTest/assets").asFile)
        listOf("gpu-android-arm64.zip", "gpu-libredwood-gl.dylib", "gpu-stage-archive.py").forEach {
            require(gpuAssets.resolve(it).isFile) { "Missing experimental GPU asset: $it" }
        }
        sourceSets.getByName("main").assets.srcDir(gpuAssets)
    }
    namespace = "com.android.gudao.iphoneos"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        // Restore the original HackiOS application identity for the 0.4 release.
        applicationId = "com.android.gudao.iphoneos"
        // Glasmio (iOS 27 Liquid Glass) requires API 33; the app is an Android 13+ deliverable.
        minSdk = 33

        // targetSdk 28 is deliberate and must not be raised. See docs/engineering-notes.md:
        // Android 10+ derives the SELinux domain from targetSdkVersion, and untrusted_app_29+
        // carries a neverallow on executing files in the app home directory. The emulator runs
        // bash/python3/7zz/unzbm/qemu straight out of /data/user/0/<pkg>/files/run, so the app has
        // to stay in the untrusted_app_27 domain. Verified on the AGP 9.4.1 / compileSdk 37
        // toolchain: minSdk 33 + targetSdk 28 is accepted and merges as
        //   <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="28" />
        targetSdk = 28

        // 0.28.0：合并外部项目调研的稳定性改动（FAST 作业集、virtio_net、宿主内存预检、
        // EFI NVRAM 自愈、137/139/134 退出码归因）。
        // 0.28.1：依据 2026-11-05 真机日志，FAST 作业集补 com.apple.securityd +
        // SystemConfiguration.configd，终止 mobileactivationd 的 SIGTRAP 连崩。
        // 0.28.2：同一序列里还缺 com.apple.timed（com.apple.timed.xpc），一次补齐激活链。
        // 0.29.1：configd 的 job 名从 MachService 名改为 label com.apple.configd。
        // 0.29.2：QEMU 在打开 -serial 前 exit=1 的自愈（保守档重试）+ 失败卡片显示 qemu.log
        // 原文与启动预算；不改镜像内容，IMAGE_REV 保持 8，用户无需重编 20–40 分钟的镜像。
        // 0.29.3：修 -netdev 的 dns= 多值拼接错误（QEMU 按逗号切分导致 exit=1、串口都没开）。
        // 0.29.4：修 guest 触摸节点名不一致（kernel 建 /dev/hakios-touch，rwtouch /
        // libredwood 却找 /dev/redwood-touch），自动上滑与触摸库恢复；IMAGE_REV 升 9 触发 root.apfs 重建。
        // 0.29.5：宿主算力池不再写死 `0-(N-1)` 掩码——先读 /proc/self/status 的 Cpus_allowed_list
        // 与各核 cpufreq，再在「系统放行的核」里按频率优先选大核（如 6-7），系统本就放行全部核
        // 或 setaffinity 被拒时不钉核、TB 缓存照旧生效；同时把「合成器 2 帧后停摆」的错误归因
        // 纠正为「softpipe 太慢」，自动降核阈值 120s→15min。不改镜像，IMAGE_REV 保持 9。
        // 0.29.6：性能闸门（8 Gen 2 / 天玑 9200 / 玄戒 O1 及以上更佳，弱于该档可能开机半小时
        // 或引发 LSD 风暴），每次打开弹一次；日志页去掉永远为空的 QEMU 分段，清除日志入口
        // 挪到右下角，运行时置灰、停止后询问是否删除。改动只在 App 层，不动镜像。
        // 0.29.7：启动预算改成实测值（Logo 60s / 桌面 600s；旧值 90s/180s 从未达成过），
        // 「亮 Logo」从 loader 画的开机图改为串口 `redwood-gl: frame` 触发的苹果 Logo。
        // 0.29.8：引导期取帧从 2 FPS 降到 1 FPS，把 CPU 让给 TCG。
        // 0.29.9：亲和性兼容性修复。Android 的 /system/bin/taskset 是 toybox 版，只认十六进制
        // 掩码（`taskset c0`），不认 util-linux 的 `-c 6-7`；旧探测只按 util-linux 试，于是在
        // 绝大多数手机上首轮就失败、整体降级。现在两种语法都试，并用子进程打印的
        // Cpus_allowed_list 验证「真的钉住了」而不是只看退出码。
        // 0.30.0：内核资产换成带 bootlog 叠层的重建内核（SHA-256 7b1f9083…），托管 cmdline
        // 追加 bootlog=1；旧 payload 由 CMDLINE_PROFILE=6 强制重烘。取帧上限同时从 8 FPS 提到 60。
        // 0.30.1：Adreno 材料（stage.tar / root-gpu.apfs / dylib）与 materials.txt 全部发布到
        // /storage/emulated/0/Download/hackios/gpu/adreno-v2/<key>/，清 App 数据后仍可复用。
        // 0.31.0：首页承载驱动与存储。系统渲染后端改成两档下拉（OSMesa / Adreno），启动时用
        // EGL 读 GL_RENDERER 识别 GPU：只有高通 Adreno 8xx 能选 Adreno，8xx 非 830 先提示
        // 「理论上 8XX 通用，但仅在 830 上实测」。导入驱动功能整体删除。guest 画面不再叠加
        // 串口日志（cmdline 去掉 bootlog=1，CMDLINE_PROFILE 升 7 强制重烘 payload）。
        // 0.31.1：UI 一致性/操作审查（docs/ui-review-0.31.1.md）。修可写盘选不中、改容量偷换模式、
        // 启动中按钮可点、屏幕页状态文案错误、危险操作无确认等；补齐主流程入口。不改 QEMU 参数。
        // 0.31.2：把「画面到底有多快」变成可测量的数。取帧耗时拆成 QEMU 截屏（dump）与 App
        // 解码（decode）两段单独显示；新增内容帧率（3 秒窗口内画面指纹变化次数 ÷ 实际窗口时长），
        // 和采样率分开显示——用户要的 20 FPS 指内容帧率，采样 18 帧/秒不代表画面在动。
        // 不改 QEMU 参数、不改 payload，只加度量。
        // 0.31.3：画面静止 2 秒后采样从 60 FPS 目标降到 4 次/秒（FramePacer.STATIC_*），
        // 画面一变立刻回到 16 ms。来宾 0.1–0.3 FPS 时 99% 的采样都是同一张图，
        // 每次都要 QEMU 主循环写 8 MB PPM + App 读回解码，纯属和 TCG 抢主循环。
        // 不改 QEMU 参数、不改 payload；只改采样节奏，诊断面板会显示「静止降采样」。
        // 0.31.4：A6 帧环。worker 每帧渲染完直接把 BGRA 帧写进 App 建的帧环文件，
        // 来宾侧不再收像素（`EA_READ_RGBA` 带 NO_REPLY，回复只剩 64 字节头）。
        // 依据是实测：单帧 2.03 s 里 copy-out 占 1.32 s，而它 1.55 s 的原始耗时**不是像素处理**
        // （逐字节 swap 只占 2.9%、32 位字化只省 12 ms），是 ~7000 个 1500 B 包走
        // guestfwd→slirp→virtio-net→来宾内核 TCP，~220 µs/包。减少字节动不了它，只能让像素不跨界。
        // 同时 App 不再 screendump/写读 PPM/逐像素解码（每帧 56 ms，且占 QEMU 主循环）。
        // 0.31.6：**差分回写**。surface 必须每帧等于完整帧（CA 会把它当纹理上传），但不需要
        // 每帧传 10.9 MB：宿主按 16 行一带 memcmp，只把变化的行带 + 行号表发回来宾，来宾按
        // y*stride 拷行。桌面典型帧只变一小块，回写从 1.77 s 降到几百毫秒。
        // 正确性由「把 delta 应用到上一帧 == 整帧」的模型测试钉住（test-eagl-delta.py）。
        // 顺带修正：生产路径改为带 SWAP 请求，帧环里从此是真正的 BGRA（此前 flags=0 时是 RGBA，
        // App 按 ARGB 读会把红蓝读反）。
        // 0.31.8：即开即用修复（基于 docs/first-run-audit.md）——Adreno 前置条件失败时自动回退
        // OSMesa 并明示原因，而不是给一个必然重复失败的「重试」；设置页的启动预算文案改为读
        // BootTimeline 常量（此前写死 90s/180s，与真实的 60s/600s 矛盾，正常慢启动被误判超预算）。
        // 0.31.5 修正：**不再跳过回写**。0.31.4 真机实测出现「图标一个一个冒出、界面绘制闪烁、
        // 不知名黑块」——CA 会把合成结果回写进 IOSurface，下一帧再把这个 IOSurface 当纹理上传，
        // 不回写就等于上传旧内容。所以像素必须继续同步到 surface；帧环只负责「App 怎么拿到帧」，
        // 它与「像素要不要回给来宾」是两件事。回写的传输成本（7000 个包）留给差分/影子纹理解决。
        // 详见 docs/gl-frame-ring.md 的「跳过回写为什么关掉」。
        versionCode = 68
        versionName = "0.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // The two pflash images are *sparse* 64 MiB files: almost entirely zeroes. The previous build
    // listed "fd" in noCompress, so both were stored verbatim and the APK carried 128 MiB of zero
    // bytes. They compress to a few hundred KiB. The zips are already deflated, so they stay stored
    // and extraction remains a straight byte copy.
    androidResources {
        noCompress += listOf("zip")
    }

    defaultConfig {
        ndk {
            // The bundled QEMU is aarch64-only; x86_64 is kept so the UI can still run on an
            // emulator. armeabi-v7a/x86 cannot execute the payload and only bloat the APK.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Debug dex is ~67 MB because nothing is shrunk and the Compose runtime is kept whole.
            // R8 strips the unused half of Compose/Glasmio; assets are never touched by shrinkResources
            // (only res/), so the QEMU bundle, toolchain zips and firmware stay byte-identical.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // android.util.Log is a stub on the JVM; the download engine logs, and its end-to-end
            // test (real HTTP server, real chunk files) runs there.
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        // The whole point of targetSdk 28 is explained above; the lint check cannot know that.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    // ---- iOS 27 UI framework ---------------------------------------------------------------
    // glasmio-scaffold transitively brings glasmio-ui, glasmio-glass, glasmio-core and
    // glasmio-icons, i.e. Liquid Glass materials, the full control set and the navigation
    // scaffold. glasmio-preference supplies the grouped preference rows used by Settings.
    implementation("io.github.neoruaa.glasmio:glasmio-scaffold:0.1.1")
    implementation("io.github.neoruaa.glasmio:glasmio-preference:0.1.1")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // ---- unit tests (JVM) -------------------------------------------------------------------
    testImplementation("junit:junit:4.13.2")
    // 单元测试跑在 JVM 上，android.jar 里的 org.json 只是一个空壳（isReturnDefaultValues=true
    // 之后每个方法返回 null/0）。MCP 协议层全靠 JSON 拼装与解析，必须用真正的实现来测。
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    // ---- instrumented / Compose UI tests ----------------------------------------------------
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.1")
}
