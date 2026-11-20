# HackiOS 0.3.5-gpu-test2

修复 `python3 gpu-root-overlay.py` 的退出码 126：从 Bash 直接执行 Python 二进制，不再把它当成 Bash 脚本。已用 APK 内置工具链复现旧错并验证生成独立 Adreno APFS。

这是独立交付的 Android ARM64 实验包（同包名，versionCode 54），可覆盖安装上一测试版。未主动启用 Adreno 时默认为 OSMesa。启用路径：**设置 → 驱动 → 系统渲染后端 → Adreno**，在虚拟机停止时切换。若提示缺少匹配的根镜像材料，在本测试版的「资源」页用已有 IPSW 重新编译一次；Adreno 会有独立根镜像缓存。关闭 Adreno 后仍可使用 OSMesa，无需为切回后端而重编镜像。

Adreno 启动固定双 vCPU / MTTCG，SLIRP 只向来宾映射 GPU 服务，屏蔽外网且不挂载数据盘。启动前检查 GPU 身份和绘制回读，失败或 worker 退出会报错，不会悄悄用 OSMesa 重试。日志导出附带 `gpu-self-test.log` 和 `gpu-worker.log`。不要通过降级覆盖来切回稳定版：保留数据时请先用测试版中内置的 OSMesa。

独立真机环境已验证 Adreno 830 的 SpringBoard 和点击后真实设置页面：来宾 CoreAnimation 请求由 Zink/Turnip 执行，回读像素经 IOSurface 提交显示。普通 APK 的 Android 应用域尚需在真机验证；自检成功不代表系统画面已通过。该路径不是零拷贝，也不保证比 OSMesa 快。验收时请同时核对实际桌面、点击后的页面和 worker 有效读回，而不是仅看自检、黑屏或启动进度。

测试构建用 `-PgpuTest=true`，要求在 `app/src/gpuTest/assets` 提供 `gpu-android-arm64.zip` 和 `gpu-libredwood-gl.dylib`。关闭该项目属性的构建不打包这两项。驱动导入仍是数据型接口，不执行导入包中的代码。
