# 设计系统 · iOS 27 Liquid Glass

界面由 **Glasmio 0.1.1** 提供材质与控件几何，本仓库只补充语义色、壁纸与页面结构。所有圆角都是
`ContinuousRoundedRectangle`（连续曲率 / squircle），由 Glasmio 的 `GlasmioShapes` 统一注入，组件内不
单独写 `RoundedCornerShape`（少数自定义容器除外，已统一取 18/26/34 pt 三档）。

## 1. 颜色

iOS 系统色而不是 Material 调色板：

| Token | 深色 | 浅色 | 用途 |
|---|---|---|---|
| `blue` | `#0A84FF` | `#007AFF` | 主操作、进度、链接 |
| `green` | `#30D158` | `#34C759` | 运行中 / 校验通过 |
| `orange` | `#FF9F0A` | `#FF9500` | 进行中 / 可续传 / 警告 |
| `red` | `#FF453A` | `#FF3B30` | 失败 / 破坏性操作 |
| `purple` | `#BF5AF2` | `#AF52DE` | 壁纸 |
| `teal` | `#64D2FF` | `#32ADE6` | 壁纸 |

实现：`ui/theme/Theme.kt` 的 `IosAccents*` + `LocalIosAccents`；基础语义色（content / secondaryContent /
groupedBackground / separator / destructive）全部来自 `GlasmioTheme.colors`，不自定义。

## 2. 字体

`glasmioTypography()` → `IosTypography` 的 11 级字号（LargeTitle 34 / Title2 22 / Headline 17 /
Body 17 / Subheadline 15 / Footnote 13 / Caption1 12 / Caption2 11）。

* 不打包 SF Pro（Apple 许可限制）。需要在真机上 1:1 还原时，把授权字体放入 `res/font/`，在
  `setContent` 外层调用 `GlasmioIconProvider(symbolTypeface = ...)`，并把 `glasmioTypography(fontFamily)`
  指向该字族即可，页面代码无需改动。
* 字重只使用 Regular / Medium / Semibold，不用 Bold —— iOS 的层级靠字号与颜色，不靠加粗。

## 3. 圆角与间距

| 元素 | 圆角 |
|---|---|
| 分组列表 / 卡片 | 26 pt |
| 弹窗 / Action Sheet | 38 pt |
| 底部标签栏 / 控制条 | 24–34 pt |
| 小控件（按钮、胶囊） | 全圆角 |
| 行内图标底 | 16–18 pt |

页面水平内边距 16 pt（`IosPageHorizontalPadding`），分组间距 10 pt，分区间 32 pt。

## 4. 液态玻璃

材质本身完全交给 Glasmio：

```
GlasmioBackdropHost(
  sampledContent = { IosWallpaper() },   // 被折射的背景
  overlayContent = { ... 全部 UI ... }   // 玻璃浮在其上
)
```

* **背景必须是可采样内容**：`IosWallpaper` 用 4 个缓慢漂移的径向渐变（26 s 往返）提供大面积、低对比的
  亮度变化。玻璃需要有东西可折射，纯色背景下玻璃会退化成半透明灰块。
* **层叠顺序**：玻璃控件 → 内容 → 弹层（`GlasmioBottomSheet` 自带 dim + 背景采样），不允许在玻璃上再叠
  一层不透明背景（那会让折射消失）。
* **高光**：由 `PressHighlight` / `InteractiveHighlight` 在按压点生成镜面高光与光泽扫过；本仓库的
  `GlassKey`、`GlassActionButton` 另外加了 0.90–0.96 的按压缩放，二者叠加即为 iOS 的「按键被按下去」。
* **降低透明度**：`GlassAppearance(reduceTransparency = true)` 可直接切到不透明回退，无障碍场景使用。

## 5. 动效

| 场景 | 参数 |
|---|---|
| 按压缩放 | `spring(dampingRatio = 0.5–0.55, stiffness = Medium/High)`，scale 0.90–0.96 |
| Push / Pop 转场 | 进入 `spring(0.86, MediumLow)` 水平位移，退出 1/4 位移 + fade |
| 标签切换 | `Crossfade`（iOS 根标签是淡入，不是滑动） |
| 进度条 | `spring(0.9, MediumLow)`，不允许线性跳变 |
| Toast | 顶部下滑 `spring(0.75, MediumLow)`，3.2 s 自动消失 |
| 壁纸漂移 | 26 s 线性往返，刻意慢于任何交互动效 |

## 6. 控件映射

| iOS 控件 | Glasmio API | 本仓库使用处 |
|---|---|---|
| 分组列表（Inset Grouped） | `GlasmioGroupedList` + `GlasmioListRow` | 设备环境、资源镜像、设置各项 |
| 可折叠大标题 | `GlasmioPinnedListPage` | 设备 / 资源 / 设置 |
| 底部标签栏 | `GlasmioNavigationBar` + `GlasmioTabItem` | 根导航（屏幕页沉浸式隐藏） |
| 主按钮 | `GlasmioButton(emphasis = Prominent)` | 启动 / 停止、编译、自检 |
| 图标按钮 | `GlasmioIconButton` | 顶栏复制日志 |
| 开关 | `GlasmioSwitch` | 虚拟网络、日志跟随 |
| 滑杆 | `GlasmioSlider` | 处理器核心 1–8 |
| 分段控件 | `GlasmioSegmentedControl` | 外观（自动/浅色/深色） |
| Action Sheet | `GlasmioBottomSheet(fitContent = true)` + `GlasmioActionSheetContent` | 画面「更多」 |
| Alert | `GlasmioAlertDialog` + `GlasmioAlertButtonSpec` | 停止确认 |
| 卡片 / 玻璃面 | `GlasmioCard`、`glasmioGroupedListBackground` | Hero 卡、Banner、进度卡 |

Glasmio 未提供的（电源 / 锁屏 / 音量 / 显示 / CPU / 网络 / 文件夹 / 终端图标、细进度条、状态胶囊、
错误 Banner、Toast）在 `ui/components/` 内按同样的 1.75 pt 圆头描边自绘，避免混入 Material 的实心图标。

## 7. 信息层级

每一页遵循同一结构，参考 FlClash 的「一眼看到状态」：

1. **状态**：我现在的状态是什么（运行中 / 已暂停 / 失败原因）；
2. **主操作**：下一件该做的事，只有一个；
3. **细节**：分组列表给出数值与路径；
4. **说明**：脚注解释代价（如「约 20–40 分钟」）。

失败状态永远给出「发生了什么 + 为什么 + 下一步」，这也是 `GlassBanner` 强制三参数的 API 设计原因。
