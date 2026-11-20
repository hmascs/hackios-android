# HackiOS GPU：OpenGL ES 与 Vulkan

## 已实现、未实现

- 宿主后端 `egl-gles3-pbuffer` 使用 EGL + GLES 3；`vulkan-rgba8` 使用 Vulkan 1.0 图形队列、内置 SPIR-V、离屏 RGBA8、fence 和 staging 回读。两个模块暴露相同的宿主进程 ABI v1，各自拥有设备、上下文及资源；在启动时选一个，不在运行中切换，不偷偷降级到另一套后端。
- 真正的 iOS Mach-O 探针经 virtio-net、受限 SLIRP 和私有 Unix socket 提交同一个定长绘制命令。宿主实际画三角形，iOS 校验前景/背景像素后才发送 ACK。
- **SM8750 硬件验收通过**：Android ARM64 Bionic 进程通过 Mesa 26.2.7 Turnip 访问 `/dev/kgsl-3d0`。VK 枚举 `Adreno (TM) 830 [vendor=0x5143 device=0x44050001 type=1]`；GLES 经 Zink 报告 `zink Vulkan 1.4(Adreno (TM) 830 (MESA_TURNIP))`。两套探针都在实际 XNU 客体收到并校验了硬件像素。GLES 路径复用 Vulkan 硬件驱动，不是 Qualcomm 原厂 GLES 实现，也不是 llvmpipe。
- 上述独立探针协议只有一个受限的三角形操作，不代表系统加速。下文另有 Redwood/EAGL 系统合成实验后端；它不是通用 GLES/Vulkan 分发层，现有默认渲染保持不变。
- PRoot 中 `/vendor` 和 `/linkerconfig` 仍不可用，直接运行系统图形 loader 的尝试没通过。上述通过的是独立 Bionic Turnip/Zink 方案，没有改系统驱动；普通 Android APK 部署与厂商 loader 尚未验证。

## 在本机复测 Adreno（不需要 ADB）

准备一个可读的 ARM64 Bionic Termux 前缀，包含 `mesa-vulkan-icd-freedreno`（KGSL 版）、`vulkan-loader-generic`、`mesa`、`libglvnd` 及依赖。用于本次实测的是 [Termux 镜像的软件包](https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/)，在隔离目录解包，未安装或替换系统文件。Mesa 和 Turnip 为 `26.2.7`，通用 Vulkan loader 为 `1.4.368`。构建生成独立 manifest，只选指定的 Turnip ICD，避开 glibc 系统的 Vulkan layer。

```sh
bash qemu/gpu/build-android-kgsl.sh NDK_LLVM_BIN TERMUX_PREFIX GPU_OUT
python3 qemu/gpu/test-qemu-serial.py /path/to/qemu-system-aarch64 \
  --backend vk --native-worker GPU_OUT/turnip-worker.sh --expect-renderer Adreno
python3 qemu/gpu/test-qemu-serial.py /path/to/qemu-system-aarch64 \
  --backend gl --native-worker GPU_OUT/zink-worker.sh --expect-renderer Adreno
```

对完整 XNU 复测，将相同的后端和 `--native-worker`、`--expect-renderer` 选项传给下方的 `test-xnu-guest.py`。软件回归仍单独保留。

同机已经以 2 和 4 vCPU 分别复测：两次都在启用只读 virtio 数据盘时通过 GLES/Adreno 像素探针，之后用 `--desktop-reference` 核验到 1125×2436 的 SpringBoard 主屏。它证明三项可以共存；串口同时明确记录 CoreAnimation 上下文是 `softpipe`，因此**不是 GPU 加速桌面**。

系统接入以 `EAGLContext` 真正的上下文创建、绘制提交和 IOSurface 更新为边界。不能只用软件渲染好的图像在宿主再 blit 一次来冒充组件加速。单条指令遇到不支持的对象状态，不能无提示在同一帧中切回软件。

### CoreAnimation 接线进度

- 用 `test-xnu-guest.py --trace-gl-frames 1-13 --desktop-reference PATH` 追踪真实 SpringBoard，再用 `summarize-eagl-trace.py SERIAL_LOG` 统计。一次成功进主屏的运行记录了 13 帧、925 条完整命令、35 种调用；其中 `glDrawElements` 47 次、`glDrawArrays` 2 次。记录来自仍使用 OSMesa 的基线，是实际调用清单的下界，不包括 Redwood 未记录的内部 GL 调用（例如 IOSurface 回读），不是硬件帧。
- `eagl-client-arrays.*` 与 `eagl-host-arrays.c` 实现常见客户端数组转发的编解码和实际 GLES 提交：验证索引与客体缓冲界限、剥除交错布局的间距，宿主在自己的 EGL 上下文重建顶点和索引缓冲；不传客体指针。不支持 VBO 混用、实例属性、primitive restart 或使用 `gl_VertexID` 的着色器；后者必须先排除或换不会重编号的路径。
- `eagl-pixel-transfer.*` 与 `eagl-host-pixels.c` 提供去除行跨度的 BGRA8、Alpha8、LA8、RGBA16F 纹理上传和 RGBA 帧回读写回 BGRA 的原语。`bash qemu/gpu/test-eagl-guest-handoff.sh REDWOOD_BUILD_OUTPUT` 检查客体跨线程调用顺序及 LA8 纹理逐行打包。Adreno 830（Zink → Turnip）已通过独立测试，校验真实着色器采样、GPU 绘制像素、前后 GL 状态及行翻转；`bash qemu/gpu/test-eagl-arrays.sh` 为 llvmpipe 软件对照，真机脚本是 `build-android-kgsl.sh` 生成的 `zink-arrays-test.sh Adreno`。
- 宿主双向会话已在 Adreno 通过独立绘制、同步和回读验证，也能编译链接串口采集的第一对真实 CoreAnimation GLSL。客体转发模块已交叉编译，`prepare-redwood-remote.py` 可生成显式启用 IOSurface 回读的 Redwood 源码副本。默认软件路径不变。
- `build-redwood-remote.sh REDWOOD_USERLAND IOS_SDK GL_XML BASELINE_OSMESA NEW_OUT` 用 Redwood 源码、SDK 与基线 GL 导出表构建替代库，链接产物不依赖 OSMesa；未实现的 GL 入口记录名字并退出，不回退软件。已在隔离客体确认库加载、EAGL 方法被替换，并在下面记录的隔离启动中通过主屏与设置交互验收（不等于 APK 域部署）。启动初检使用 `test-xnu-guest.py` 的 `--remote-redwood DYLIB --remote-worker ZINK_SESSION_SH --desktop-reference REFERENCE`，只在新复制的根镜像安装替代库，不运行三角探针，要求同次启动同时出现主屏、客体上下文记录和真实 Adreno 主屏尺寸的绘制回读。客体 PID 必须与 launchd 启动的 backboardd 或 SpringBoard 一致。主屏匹配后，测试器在原 QMP 会话中滑到另一页再返回，每次要求稳定、可区分的扫描画面和递增的全尺寸 Adreno 回读序号；旧帧或小尺寸探针不通过。
- 真机隔离启动 `redwood-program.la3Wd1`：backboardd PID 11 使用 Adreno 830，宿主回读 4 张 1125×2436 帧（各有 3、3、2、3 次绘制）；客体记录了 IOSurface 写回及 IOMobileFramebuffer swap，QMP 截图仍是启动图，未到可交互主屏。随后遇到不支持的 `glGetFloatv` 而退出。已在 `redwood-clear-color.hQu4bZ` 构建并单独验证 `GL_COLOR_CLEAR_VALUE` 转发；该修复已在 `redwood-clear-boot.CacC8s` 重启验证，同一合成进程完成了 8 张全尺寸 GPU 帧，随后首次上传 `GL_LUMINANCE_ALPHA` 时退出。`redwood-la8.WNIrO2` 补齐 LA8（宿主 RG8，采样后为 L,L,L,A）并通过 Adreno 像素测试。后续真机启动 `redwood-la8-verify.uWcOJi` 完成 12 张全尺寸帧与 40 次绘制，开始处理 SpringBoard 图标与文字 IOSurface，随后 `backboardd` 在 `glTexImage2D` 中退出，尚未通过主屏测试。新版本允许重叠源行并记录失败阶段、纹理参数及宿主拒绝码。带 `--trace-gl-frames 10-22` 的 `redwood-unpack-verify.bTYVb3` 在第 10 帧的 Redwood 跟踪查询 `glGetTexLevelParameteriv` 处退出，早于原 LA8 失败点，不能据此判定原问题已修复。`redwood-texquery.Rj9BIZ` 已实现两种纹理查询，并在 Adreno 验证真实尺寸、存储格式、过滤和环绕参数。无详细跟踪的 `redwood-texquery-verify.gDZs4k` 随后确认原 LA8 调用为 256×256、`level=1` 的 mip 上传，不是行跨度拒绝。`redwood-la8-mip.a8YAv8` 保留第 0 层协议，并新增有界 mip 上传；真机回归验证基础层和第 1 层的尺寸及不同实绘像素，后续 `redwood-mip-verify.HIQsBY` 的 `backboardd` 已越过该纹理调用，完成 23 张全尺寸帧与 92 次绘制，接着在 `glUniform2fv` 退出。最后一张 QMP 截图仍是启动画面。`redwood-vec2.3Uxf2B` 实现了该调用，并用客体编组测试和通过 Adreno 着色器选中已知纹理像素的方式验证；随后实机要求 `glUniform3fv`，于是补齐 1–4 分量浮点数组并再次验证像素。`redwood-float-vectors-verify.lURbZv` 的 `backboardd[11]` 完成 25 张 1125×2436 硬件帧；QMP 在 505.11 秒采集到 SpringBoard 主屏，图标区与软件基线的平均 RGB 差为 0.08。随后首次滑动阶段，客体在 `glDrawArrays` 的 `submit` 退出，未采集到交互后画面。`redwood-draw.tGGwGt` 新增无顶点数组直接绘制（保留 `gl_VertexID`）及 GLES 合法图元范围，并在 Adreno 验证程序化绘制；下一次隔离开机 `redwood-draw-verify.Xr3AEL` 再次捕获主屏（图标区差 0.16），完成 47 张全尺寸硬件帧，随后在 Redwood 为滑页执行八倍缩采样的分支查询 `glGetVertexAttribiv(GL_VERTEX_ATTRIB_ARRAY_ENABLED)` 时退出。`redwood-vertex-query.UR43EH` 从客体状态读取该值（不能用宿主暂存 VAO 的状态），已通过跨线程保存／恢复和无数组绘制编组回归；带该修复的 `redwood-vertex-query-verify.ftVKRp` 完成 67 张全尺寸硬件帧、再次显示主屏（差 0.18），未出现合成器崩溃，但滑动没有切换到稳定的另一页。按 QEMU PPM 行对齐还原的最后一帧仍是主屏，Notes 图标变暗，不足以认定滑页成功。运行器新增 `--remote-interaction settings`：同次启动点击设置，要求稳定的不同画面及更新的全尺寸 Adreno 回读，并保存待目视确认的截图。首次设置试跑 `redwood-settings-verify.w4iQHJ` 匹配主屏（差 0.21），点击后全尺寸回读从 26 增至 47；截图却是仅含电量提示和 Home 指示条的黑色过渡，**不算设置打开**。验证器现在拒绝缺少内部内容的帧，继续等待设置实画面。

### 同次启动验收：SpringBoard → 设置

`redwood-settings-content.M5VER6` 使用 2 vCPU、显式 Adreno 后端，在同一次隔离启动中确认：

1. launchd 启动 `backboardd[11]`（串口第 737 行）；该 PID 打开客体 EAGL 远程上下文 1（第 973 行）。
2. 对应 worker 报告 `zink Vulkan 1.4(Adreno (TM) 830 (MESA_TURNIP))`。首次实际 1125×2436 回读包含 3 次绘制；串口第 1170–1175 行记录提交和 IOSurface 输出，第 2970 行记录 BGRA 显示交换。
3. `desktop.png` 匹配可识别的主屏。点击设置后，launchd 启动 `Preferences[37]`（第 3116 行）；`desktop-after-settings-tap.png` 肉眼确认显示 Settings、Wi-Fi、Notifications 等内容，不是黑色转场。GPU 回读从 25 前进到 48；最后一张全尺寸帧有 110 次 GPU 绘制。

这是独立 Bionic 宿主上的真实系统合成，不是探针或软件回退；每帧仍经过 socket 回读复制。普通 Android APK 域、默认启用和加速倍率未验证；0.3.5 仍使用 OSMesa。

### 回读耗时分解（可选诊断）

新构建的测试 worker 支持 `HACKIOS_EAGL_PROFILE_READBACK=1`。成功的 `EA_READ_RGBA` 额外记录 `seq`、`read`、`bytes`、`host_dispatch_us` 和 `socket_write_us`。前者包含宿主准备、`glReadPixels` 及其同步，后者是把回包交给 Unix socket（可能因 QEMU 背压等待）；后者不等于客体收到完整数据的耗时，也不含客体的 RGBA→BGRA。默认关闭，不影响回包或渲染结果。Ubuntu llvmpipe 协议回归验证开关两边的着色像素、上下文隔离及 4 个成功回读的一一对应；这是计时接口验证，不是 Adreno 性能结论。

## Linux/Ubuntu 回归

依赖：C 编译器、`pkg-config`、EGL/GLES/Vulkan 开发包和 Mesa 软件渲染驱动。Vulkan 测试默认选择 Lavapipe ICD；有多个同名文件或路径不同，可设置 `HIOS_LAVAPIPE_ICD`。生成着色器另需 `glslangValidator`、`spirv-val` 和 Python；正常构建使用源码树中的 SPIR-V 字数组，无运行时着色器编译依赖。

```sh
bash qemu/gpu/test-host-renderer.sh
python3 qemu/gpu/test-qemu-serial.py /path/to/qemu-system-aarch64 --backend gl
python3 qemu/gpu/test-qemu-serial.py /path/to/qemu-system-aarch64 --backend vk
```

真实 iOS 测试读取现有的 iOS/QEMU 资产，在**新目录**复制可写根卷及 EFI 状态，不覆盖原始镜像。将 `vk` 换成 `gl` 并选另一个新目录可对照。宿主身份和 ACK 保存到 `worker.log`，iOS 结果保存到 `serial.log`。

```sh
python3 qemu/gpu/test-xnu-guest.py QEMU_RUN RAM_IMG PAYLOAD ROOTFS MKAPFS NEW_TRIAL \
  --ld64 /path/to/ld64.lld --backend vk
```

## 可选：Android 厂商 driver 路线（未通过本机验收）

用 Android NDK 构建（arm64/API 33、16 KiB ELF 段对齐）：

```sh
bash qemu/gpu/build-android.sh /path/to/ndk/toolchains/llvm/prebuilt/HOST/bin ./gpu-out
```

如果测试主机本身能执行 Android 原生程序，可直接用 `--native-worker /path/to/hackios-vulkan-worker --expect-renderer Adreno`（GL 用 `hackios-egl-worker`）。不要把 PRoot 里的 `/system/bin/linker64` 可启动当作 vendor driver 已加载。

也可在本人授权并已经连接 ADB 的 SM8750 手机原生 shell 中运行工作进程。下面只部署两份独立测试文件，不安装 APK、不改用户镜像。不用 PTY，避免对二进制命令做换行转换：

```sh
adb devices -l
export HIOS_ADB_SERIAL='实际设备 serial'
adb -s "$HIOS_ADB_SERIAL" shell mkdir -p /data/local/tmp/hackios-gpu
adb -s "$HIOS_ADB_SERIAL" push gpu-out/hackios-egl-worker gpu-out/hackios-vulkan-worker /data/local/tmp/hackios-gpu/
adb -s "$HIOS_ADB_SERIAL" shell chmod 755 /data/local/tmp/hackios-gpu/hackios-egl-worker /data/local/tmp/hackios-gpu/hackios-vulkan-worker
```

先运行无 iOS 的传输检查（`gl`、`vk` 各一次），通过后才跑完整 XNU：

```sh
python3 qemu/gpu/test-qemu-serial.py /path/to/qemu-system-aarch64 \
  --backend vk --native-worker qemu/gpu/adb-worker.sh --expect-renderer Adreno
python3 qemu/gpu/test-xnu-guest.py QEMU_RUN RAM_IMG PAYLOAD ROOTFS MKAPFS NEW_TRIAL \
  --ld64 /path/to/ld64.lld --backend vk \
  --native-worker qemu/gpu/adb-worker.sh --expect-renderer Adreno
```

验收同时检查宿主后端 ID、**实际 renderer/device 名称报告 Adreno**、iOS 的像素 ACK。VK 日志还记录物理设备 vendor ID、device ID 和 device type，SM8750 原生 Qualcomm 设备预期 `vendor=0x5143` 且不是 `type=4`。仅能列出扩展、只通过软件渲染或仅收到 QEMU/TCP 请求，均不算通过。
