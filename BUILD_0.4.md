# Building HackiOS 0.4

The Android application ID is `com.android.gudao.iphoneos`, `versionName` is `0.4`, and `versionCode` is `68`. Use JDK 21 and Android SDK platform/build tools 37. The published APK is the signed debug variant used for the existing installation lineage; private signing keys are not part of this source release.

## Restore the open-source runtime resources from the release APK

The source checkout includes the exact three GPU assets used by the published APK. Five larger startup resources are omitted from Git. To build the same application with its runtime resources, download `HackiOS-0.4.apk` from this release, verify its SHA-256, and extract these five entries from its `assets/` directory into `app/src/main/assets/`:

| Entry | SHA-256 prefix |
| --- | --- |
| `mach.development.bcm2837` | `7b1f908324fa1315` |
| `QEMU_EFI.fd` | `5f8ef96257f27e28` |
| `QEMU_VARS.fd` | `3b6a07d0` |
| `qemu-android-bundle.zip` | `52f4d832` |
| `hackios-toolchain.zip` | `c8cb0793` |

The APK SHA-256 is `f00dd575895404a3eec155a6fea63449cc284f5fd3e9fd67741a6aeb274885ce`. The original entries are bundled runtime tools, the open-source kernel, and boot firmware; this procedure does not extract or redistribute an iOS system image or Apple SDK. Full per-entry hashes are provided in the release resource manifest.

With the resources present, run:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug -PgpuTest=true
```

This enables the GPU asset source set. Without `-PgpuTest=true`, Gradle builds the software-backend package variant without these GPU resources. The build does not download or bundle an Apple system image. The App obtains and builds its system inputs separately. A build on a new machine needs ordinary Gradle/Maven access or a populated cache; the offline flag used on the build device is not required on a clean machine.

## Guest and worker source identity

The `qemu/gpu/` source includes the frozen HTB1 implementation used for the GPU guest (`13d6431c…`) and worker (`cc7ffe7c…`), including private delta reconstruction, complete valid-pixel writeback, original vertex indices, logical texture swizzle composition, and bounded capability-negotiated command batches. `vendor/redwood-build-input/` records the modified Redwood input files and original notices. Follow the existing GPU build scripts with separately obtained compatible toolchains, SDK headers and upstream inputs. Proprietary SDKs and system images are intentionally not included.

The frozen native tests compared nine GPU cases with batching off/on and old-worker fallback: the pixel hashes and GL errors matched, with 201/153/202 wire requests. Those tests do not establish an iOS system frame rate. Android tests for the published build passed 376 tests across 45 suites. The independent staging checkout has not been rebuilt and signed with the original private key; do not claim byte-for-byte APK reproducibility solely from the source archive.

## Kernel source and storage

The companion XNU source archive contains the kernel-specific source, local changes, notices and build provenance for the bundled kernel identity. Its APSL and original per-file licenses remain separate from the Android application's GPL license.

GPU and software backends can both attach the selected data disk. A writable block device and a persistent host image do not automatically move iOS `/private/var` out of RAM. Scratch-disk filesystem/remount/restart tests are documented separately from this App source release and do not imply migration of an existing user's iOS data.
