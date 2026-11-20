# 启动等待反馈修正（工作树，编译与单测通过）

范围：设备页启动反馈与设置页预算文案，不改 QEMU 参数、内核、镜像或渲染尺寸。

- `QemuUi.Starting` 增加启动准备的起始时间；六个准备步骤共用本轮 `diagBootAt`，换步骤不重新计时。
- 设备页把计时放在独立 Compose 子组件里，每秒只刷新计时组件；切到其它页面后自动取消刷新。
- 准备中及阶段 0–6 使用不确定进度条，阶段点保留。阶段顺序不是预计耗时比例，不能用固定 6% / 86% 表示时间完成度。
- 等待说明统一为首次进入桌面可能需要 10–15 分钟或更久；准备阶段提示保持 App 前台，不承诺后台服务保障。
- 设置页剩余的桌面“预算 180s”改为读取 `BootTimeline.DESKTOP_BUDGET_MS`。
- 移除检查 Adreno 步骤中与实际自动回退行为冲突的“无软件回退”字样。

计时边界：Starting 计时表示资源部署/准备阶段；Running 仍使用现有进程运行起始时间，不改变 BootTimeline 的分段计量。界面就绪仍沿用原先真实帧判定，不新增桌面检测规则。

验证：检查了六个 Starting 构造点及进度组件接口；`git diff --check` 通过。通过同一实验锁串行执行 `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 ./gradlew :app:testDebugUnitTest --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m`，BUILD SUCCESSFUL（1m39s）；JUnit XML 汇总 43 个测试套件、355 项测试，0 失败、0 错误、0 跳过。尚未做真机界面验证，未发布新 APK。

本改动不构成 CPU 性能优化，内容帧率结果由独立的 QEMU/内核实验提供。
