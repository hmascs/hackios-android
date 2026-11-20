# HackiOS 0.3.5 driver packages（0.31.0 已移除）

> **0.31.0 起功能已删除**：设置页不再有「导入驱动配置或 ZIP 包」，`DriverPackageRegistry`、
> `importDrivers` / `selectDriver` 与相关单测一并删除。原因是它只能给内置驱动起别名，不能
> 带来新的能力，却让「驱动」这一块看起来像可扩展的插件系统。驱动只剩内置三项（网络、输入、
> 显示），在首页选择系统渲染后端与虚拟网络即可。下面保留 0.3.5 的原始说明，仅供查阅历史。


An imported package is a **data-only alias** for a driver shipped with HackiOS. It cannot install a driver binary, enable a new capability, change a module version, supply QEMU options or paths, or enable the experimental GPU backend. The app's built-in driver still generates every launch argument.

## Package format v1

Use a UTF-8 `.json` file with exactly these five fields, in any order:

```json
{
  "schemaVersion": 1,
  "id": "imported.example-display",
  "name": "Example display",
  "slot": "display",
  "profile": "ramfb-2d"
}
```

- `schemaVersion`: the JSON integer `1` (not a string, decimal, or exponent).
- `id`: `imported.` followed by 1–48 characters. The first is `a–z` or `0–9`; later characters may also be `.`, `_`, or `-`. IDs are unique across all slots and cannot override built-in IDs.
- `name`: nonblank display text, up to 80 UTF-16 code units, without leading/trailing whitespace, control or Unicode formatting characters, or invalid Unicode escapes. It is not a launch argument.
- `slot` and `profile`: exact, case-sensitive values from this table.

| Slot | Allowed profile → built-in driver ID |
| --- | --- |
| `network` | `virtio-network-user` → `virtio-network-user`; `network-none` → `network-none` |
| `input` | `virtio-touch-keyboard` → `virtio-touch-keyboard` |
| `display` | `ramfb-2d` → `ramfb-2d` |

Unknown and duplicate fields are invalid, including attempted `args`, `path`, or `config` fields. There are no user-defined profile parameters in v1.

A `.zip` bundle contains 1–16 top-level JSON manifests with distinct filenames (case-insensitive). Entry names start with an ASCII letter or digit, then may contain ASCII letters/digits, `_`, or `-` (1–63 characters before `.json`). No directory entries, nested paths, or other file types are accepted; entries are validated in memory, never extracted or executed. A manifest is at most 8 KiB; the compressed archive is at most 256 KiB, and expanded manifests total at most 128 KiB. Malformed archives, integrity mismatches, wrong-slot profiles, unsupported fields, and ID conflicts reject the entire import.

## App integration

Use `DriverPackageRegistry(File(context.filesDir, "driver-packages.json"))`. `importPackage(filename, input)` uses only the filename's extension and does not close the stream. Call `list()` for installed aliases (sorted by ID), and `remove(id)` to remove one; built-ins are not removable. Import does not select any driver automatically. Resolve a saved selection with `resolve(slot, id)` before calling the built-in selector. It returns a trusted built-in ID, or `null` for a missing or wrong-slot selection. Do not put the imported ID, display name, or profile string directly into QEMU arguments.

The registry accepts at most 64 packages and a 64 KiB persisted snapshot. Operations are serialized within the app process. Validation precedes replacement; updates sync a same-directory temporary file and atomically replace the old snapshot (with no non-atomic fallback). Corrupt registries fail closed for imported aliases and are not silently overwritten; direct built-in resolution remains available. Validation/conflicts throw `IllegalArgumentException`; storage and corruption can throw `IOException`. Use app-private storage and perform I/O off the UI thread. This registry is not a multiprocess writer protocol.
