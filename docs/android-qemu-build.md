# 在这台 Android 手机上重编 bionic 版 QEMU（0.31.0 起可行）

目标：不借电脑，直接在手机（aarch64 / Ubuntu proot，clang 18 + NDK 27）上编出能在 App 里跑
`qemu-system-aarch64`（interpreter `/system/bin/linker64`），这样底层 TCG 补丁才能进 APK。

> 结论先说：**编译链已打通并成功 configure**（2026-11-19）。这条路成立的两块拼图——
> ① Ubuntu 的 clang 是目标无关的，加 `--target=aarch64-linux-android33` + NDK sysroot 就能编
> bionic 二进制；② QEMU 依赖的 glib/pixman/slirp 直接从 Termux 仓库取预编译包（含头文件与 .pc），
> 不用自己交叉编 glib。

## 1. 编译器的落地（已实测）

```sh
NDK=/root/android/sdk/ndk/27.2.12479018
SYS=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot
RT=$NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/18
clang --target=aarch64-linux-android33 --sysroot=$SYS -resource-dir=$RT -rtlib=compiler-rt hi.c -o hi
./hi   # bionic hello，在 proot 里直接能跑（/system/bin/linker64）
```

要点：
- `-rtlib=compiler-rt` 必须加：Ubuntu clang 默认找 `-lgcc`，NDK 没有。
- `-resource-dir` 指向 NDK 的 clang 18 目录，否则找不到 `libclang_rt.builtins-aarch64-android.a`。
- **不要**加 `-fuse-ld=lld`：QEMU 用 `-Werror=unused-command-line-argument`，编译 `-c` 时会因此报错；
  Ubuntu 的 GNU ld 链接 bionic 二进制正常。

## 2. QEMU 的依赖（Termux 预编译包）

从 `https://packages.termux.dev/apt/termux-main/`（`dists/stable/main/binary-aarch64/Packages.gz`
里查 `Filename`）取这些包，`dpkg-deb -x` 解到同一个 prefix：

| 包 | 用途 |
| --- | --- |
| `glib` | glib-2.0 / gobject / gio / gmodule（头文件 + .pc 都在包里） |
| `libpixman` | 显示与像素格式 |
| `libslirp` | `-netdev user`（App 的 Adreno 通道与 DNS 都靠它） |
| `pcre2`、`libffi`、`zlib`、`libiconv`、`libtasn1` | glib / QEMU 的依赖 |

解包后把 `lib/pkgconfig/*.pc` 里的 `prefix=/data/data/com.termux/files/usr` 改成本地 prefix。
**只改一次**：这个前缀里包含 `/data/data/com.termux/files/usr`，重复替换会变成
`prefix=<prefix>/root/.../prefix/...`，表现为 `-I` 路径翻倍、`glib.h` 找不到。

## 3. configure（已通过）

```sh
P=<prefix>/data/data/com.termux/files/usr
PATH=/root/workspace/android-build/bin:/opt/python/bin:$PATH \
PKG_CONFIG_PATH=$P/lib/pkgconfig PKG_CONFIG_LIBDIR=$P/lib/pkgconfig \
./configure --target-list=aarch64-softmmu \
  --cross-prefix=/root/workspace/android-build/bin/aarch64-linux-android- \
  --disable-gtk --disable-sdl --disable-opengl --disable-vnc --disable-curses \
  --disable-brlapi --disable-spice --disable-guest-agent --disable-libudev \
  --audio-drv-list= --disable-dbus-display --disable-virglrenderer \
  --disable-gnutls --enable-nettle --disable-gcrypt --enable-slirp --disable-tools
```

踩过的坑，按出现顺序：

1. 系统 meson 1.3.2 太老（QEMU 要 ≥1.5）→ `pip3 install "meson>=1.5"`，并把
   `/opt/python/bin` 放到 `PATH` 前面。
2. 缺 `bzip2` → `apt-get install bzip2`。
3. 包装器里带 `-fuse-ld=lld` → QEMU 的 `-Werror=unused-command-line-argument` 把它变成错误；去掉。
4. `pkg-config` 必须存在 `<cross-prefix>pkg-config` 这个名字（meson 按 cross 前缀找）→ 建软链。
5. 交叉文件里不要写 `[properties] sys_root` / `root`：meson 会把它们当
   `PKG_CONFIG_SYSROOT_DIR`，所有 `-I` 前面再拼一次前缀。
6. `libtasn1` 在 QEMU 10.2 的 `meson.build` 里是**无条件**依赖，必须提供。

## 4. 还差什么

- `ninja -C build qemu-system-aarch64` 是否完整链接成功，见 `docs/cpu-translation-baseline.md`
  的对应记录；编出来的二进制用 `/system/bin/linker64` + 同目录 `lib/` 运行验证。
- 这套产物目前只用于**实验与验证**：App 内置的 `qemu-android-bundle.zip` 仍是官方构建。
  要把 TCG 补丁进包，还得：替换 bundle 里的 `qemu/qemu-system-aarch64`、补齐新依赖 .so、
  在真机上跑同一套启动验收，然后才升版本发 APK。

## 5. 为了让 bionic 编过而改的源码（都带 `__ANDROID__` 或无行为差异）

| 文件 | 改动 | 原因 |
| --- | --- | --- |
| `util/oslib-posix.c` | `qemu_shm_alloc()` 在 `__ANDROID__` 下直接 `error_setg` 返回 -1，其余实现放进 `#else` | bionic 没有 `shm_open`/`shm_unlink`；App 不用匿名 POSIX 共享内存 |
| `crypto/random-platform.c` | `getrandom(NULL,0,0)` → `getrandom(&(unsigned char){0},0,0)` | bionic 把 buffer 标成 non-null，QEMU 的 `-Werror` 把 `-Wnonnull` 变成错误 |
| `fsdev/9p-marshal.h` | `#ifdef __ANDROID__` 下 `#undef st_atime_nsec/st_mtime_nsec/st_ctime_nsec` | bionic 把这几个名字定义成宏（映射到 `st_atim`），和 `V9fsStat` 的成员名冲突；9p 代码不通过宏访问 `struct stat` |

另外这条树里的 `hw/display/apple_displaypipe_v4.c` **硬性要求 libpng**（`#error "PNG support is required"`），
所以依赖清单里必须有 `libpng`（Termux 包 `libpng`，`.pc` 里 `libpng.pc` 是指向 `libpng16.pc` 的软链）。

`.pc` 归一化的两个坑：软链要按 `realpath` 去重（否则同一文件被处理两次、前缀翻倍），
旧前缀只能替换一次（新前缀本身包含旧前缀字符串）。

## 6. 当前进度与卡点（2026-11-19 17:40）

- `configure` 成功（glib 2.90.1 / pixman / slirp / libpng / libtasn1 全部找到），`ninja` 编到
  **1836 / 2021** 个目标；卡在 `hw/arm/apple-silicon/sep.c`：
  `fatal error: 'nettle/drbg-ctr.h' file not found`。
根因不是「Termux 不发布这个头」：`include/hw/arm/apple-silicon/sep.h` 无条件包含
`<nettle/drbg-ctr.h>`、`<nettle/ecc.h>`、`<nettle/knuth-lfib.h>`，而 `CONFIG_APPLE_SOC` 在
`TCG && ARM && AARCH64` 下 `default y`，`-M virt` 也照样编进 `sep.c`。所以**不能靠
`--disable-nettle` 绕开**，必须真的给头文件和库。

已解决（2026-11-19 18:05）：Termux 仓库里 `libnettle`、`libgmp` 两个包**带全部头文件**
（含 `nettle/drbg-ctr.h`、`ecc.h`、`gmp.h`）和 `.so` 软链，之前只是没解到 prefix 里：

```sh
dpkg-deb -x debs/libnettle.deb prefix3/   # include/nettle/*.h + libnettle.so.8.11
dpkg-deb -x debs/libgmp_6.3.0-2_aarch64.deb prefix3/   # include/gmp.h + libgmp.so
# 再把两个 .pc 里的 prefix=/data/data/com.termux/files/usr 改成本地 prefix（只改一次）
PKG_CONFIG_LIBDIR=$P/lib/pkgconfig pkg-config --modversion nettle gmp hogweed
# → 3.10.2 / 6.3.0 / 3.10.2
```

于是 configure 改成 `--enable-nettle`（不是 disable）。这也和 App 内置包里已有的
`qemu/lib/libnettle.so.8`、`libhogweed.so.6`、`libgmp.so` 对得上——官方那份 bundle 本来就是
带 nettle 编的，我们要复现同样的配置，而不是砍掉 SEP。

被否掉的另一条路：把 `sep.c` 从 `CONFIG_APPLE_SOC` 里摘掉。`APPLE_SOC` 是 Kconfig
`default y`、没有 configure 开关，摘它要连 `t8030.c`/`apple-soc.c` 对 `TYPE_APPLE_SEP`
的引用一起打桩，改动面比补两个包大得多。
- 已经处理掉的 Android 差异（都低风险、可回退）：`shm_open`、`getrandom` non-null、
  `stat` 的 `st_*_nsec` 宏、`scsi/sg.h` 的状态宏冲突、`apple_displaypipe_v2` 未用变量、
  `apple_displaypipe_v4` 的 libpng 依赖、`apple-silicon/boot.c` 的 lzfse（用 `CONFIG_LZFSE` 守住）。
- 编译开关里加了 `--disable-werror`：这个自定义树里 Apple 设备代码有较多未用变量/未用参数，
  宿主 gcc 不报、bionic clang 会报，逐个补不划算；发布前若要让补丁干净，可以再收紧。
