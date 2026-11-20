# LaunchServices 缺失服务重试审计

**结论：现有证据不支持阻断 lsd 的服务查询。** 检查了 44 份隔离串口文件及 1 份 Android App 导出；41 份包含 `lsd` 启动记录，19 份出现可解析的 `lsd` 查询失败；没有任何一份记录到同一服务两次独立的 `error = 3`。每份日志单独统计，不把不同虚拟机启动间的查询算作重试。

| 复现环境 | `installd` | `analyticsd` | `profiled.public` | CPU monitor 告警 |
| --- | ---: | ---: | ---: | ---: |
| Android APK 真实启动 | 1 | 1 | 1 | 701 |
| 同镜像 Inferno | 1 | 1 | 1 | 701 |
| 现版独立 Adreno 桌面 | 1 | 1 | 1 | 701 |

三项全名为 `com.apple.mobile.installd`、`com.apple.analyticsd`、`com.apple.managedconfiguration.profiled.public`。Android APK 和 Inferno 两份串口中，701 条告警均已出现在首条可解析的 `lsd` 查询失败之前；`mobileactivationd` 已有启动证据。CPU monitor 日志是 XNU 任务 CPU 策略的告警，不是 XPC 重试计数。序列和统计不能证明任何隐藏请求不存在：launchd 可能合并或抑制日志，串口也可能截断。故不能用这个结果宣称加速，不能按这三个名字静态拒绝或把它们当成已证实可跳过的服务。

## 可复查

```sh
python3 -B tools/test-audit-lsd-lookups.py
python3 -B tools/audit-lsd-lookups.py --serial /path/to/raw/serial.log
python3 -B tools/audit-lsd-lookups.py --export /path/to/hackios-logs.txt
```

审计只读；Android 导出只读实际串口段，忽略执行器进度和诊断卡的重复抄录；同一时间戳/服务/PID/错误码的重复记录计一次；分开列出不可解析的记录和 CPU monitor 告警。现有构建、镜像、权限、XPC 行为和失败返回不改。

若后续加入直接探针，需要在隔离来宾、只针对 `lsd` 收集请求方、命名空间、服务名、结果和重试时间，再逐条核对启动清单、系统卷 plist 和延迟注册。只有经该探针复现的、在特定精简构建中确定不存在且确认可选的服务，才可考虑在请求方做短寿命负缓存或去除启动期重复工作，必须保持原有失败码、不影响真实服务注册和其他请求方，并与未修改镜像做启动、主屏和 Settings 对照。权限告警降噪另验，不能将它作为重试修复证据。
