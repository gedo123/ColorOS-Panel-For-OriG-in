# 架构与技术文档

> 本文档记录本模块依赖的**协议事实**、**宿主内部结构**与**关键实现决策**，
> 便于后续维护与系统升级后的重新适配。

---

## 一、总览

```
┌──────────────────────────────────────────────────────────┐
│  ColorOS「无线耳机」面板  (com.heytap.mydevices:cards)     │
│                                                          │
│   LSPosed 注入本模块                                       │
│        │                                                 │
│        ├─ Hook 弹窗 Fragment 的视图创建                    │
│        │     └─ 把控制区 addView 进弹窗内的 contentLayout   │
│        │                                                 │
│        └─ 蓝牙 SPP (RFCOMM) ──► 耳机                       │
│              帧：[0x4E][len][0x00][opcode][params]        │
└──────────────────────────────────────────────────────────┘
```

- 模块运行在宿主的 **`:cards` 子进程**内，直接复用宿主的蓝牙权限
- 不依赖耳机厂商的官方 App
- 面板关闭即断开 SPP（RFCOMM 是独占资源）
- **适用范围**：本项目为**单机型示范**，仅针对 `YUANDAO OriG in`（固件 4.08）开发与实测

---

## 二、耳机协议（实测确认）

### 2.1 传输层

| 项 | 值 |
|---|---|
| 类型 | **经典蓝牙 SPP / RFCOMM**（不是 BLE GATT） |
| UUID | `0000a100-1000-8000-4e48-434b4354524c`（尾部 8 字节 = ASCII `NHCKCTRL`） |
| 建链 | **secure RFCOMM + 自定义 UUID 一级命中** |
| 特性 | 单连接独占；无 CRC、无序号、无 ACK |

### 2.2 帧格式

```
[0]      0x4E  magic 'N'
[1..2]   length = 3 + params.size（小端）
[3]      reserved —— 必须 0x00（实测 01/FF 完全无响应）
[4..5]   opcode（小端）
[6..]    params
```

### 2.3 指令表

| 功能 | SET | QUERY | 上行偏移 |
|---|---|---|---|
| 固件 | — | `0x0003` | `[6]`=sub `[7]`=main |
| 电量 | — | `0x0005` | `[6]`=左 `[7]`=右 `[8]`=仓（0=未知） |
| ANC | `0x0201` + `[mode, 0x00]` | `0x0101` | `[6]`=mode |
| EQ | `0x0207` + `[eq]` | `0x0107` | `[6]`=eq |
| 游戏模式 | `0x0208` | `0x0108` | `[6]` |
| 低延迟 | `0x0206` | `0x0106` | `[6]` |
| 双连接 | `0x0205` | `0x0105` | `[6]` |
| 入耳检测 | `0x0209` | `0x0109` | `[6]` |
| 抗风噪 | `0x02E1` | `0x01E1` | `[6]` |

```
AncMode: OFF=0  TRANSPARENT=1  NORMAL=2  DEEP=3  EXPERIMENT=0x10  WIND=0x11
EqMode : BLUE=0 BALANCED=1  BASS=2  PURE=3  GAME=4  FINE=5  VOCAL=6
```

### 2.4 确认机制

- 下发 SET 后，设备会回一条 **`0x02XX` 且值 = 设定值**的确认帧
- 因此可做**即时确认**，不必只靠轮询回读
- 本模块在每次 SET 后额外做一次回读校验

### 2.5 固件字节序

`[6]` = sub、`[7]` = main（例：`04 08` → 固件 4.08）。
> 注意：某些早期代码注释写成反过来，实测 `[6]=sub` 才正确。

---

## 三、ColorOS 16 面板结构（关键）

### 3.1 面板不在 melody 里

实测 ColorOS **16.0.1.301(CN01)** / Android 16：

| 项 | 值 |
|---|---|
| 宿主 | **`com.heytap.mydevices`**（设备空间 / My Devices）**16.8.5**（versionCode 1608005） |
| Activity | `com.oplus.mydevices.bluetooth.BlueToothDetailActivity` |
| Intent | `com.oplus.mydevices.ACTION_DEVICE_DETAILED_PANEL` |
| 进程 | **`:cards` 子进程** ⚠️ |
| 设备名 extra | `device_title` |
| MAC extra | `device_mac_info` |

> **关于「无线耳机」App**：它是 **`com.oplus.melody`**（标签 `Wireless Earphones`，实测 **16.10.1**）
> —— **不是** `com.oplus.com`（该包名在实测设备上并不存在）。
> 本模块的作用域同时包含它（`scope.list` 里两项），但**面板实际位于 `com.heytap.mydevices`**；
> 保留 melody 是为兼容其它机型/版本。

> ⚠️ 因为面板在**子进程**，所以 `onPackageLoaded` 阶段**不能**像其它模块那样跳过子进程。

### 3.2 面板是底部弹窗

```
COUIBottomSheetDialogFragment
   └ BlueToothPanelFragment
        └ BtPreferenceFragment        ← onCreateView / initView 是注入时机
```

**弹窗消失 = 整个 Activity 关闭**（`onDismiss → finishAfterTransition()`）。
所以**绝不能**把控件注入到 Activity 的 `android.R.id.content` ——
弹窗会把「落在自身范围之外」的触摸当作**点击遮罩**而 dismiss。

### 3.3 弹窗视图树

```
COUIPanelContentLayout id=coui_panel_content_layout
 └ LinearLayout id=panel_content
    └ LinearLayout
       └ FrameLayout id=panel_container
          └ ConstraintLayout
             └ FrameLayout id=onespace_item_list
                └ ConstraintLayout
                   └ LinearLayout id=contentLayout          ← ★ 注入点
                      ├ CoordinatorLayout                   ← 官方滚动区
                      │   └ FrameLayout id=list_container
                      │       └ COUIRecyclerView id=recycler_view
                      │          ├ [0] 耳机图 + 设备名 + 电量
                      │          ├ [1] 空占位 h=96  ← ★ 间隔条（必须保留）
                      │          ├ [2] 已连接 / 断开连接
                      │          ├ [3] 通用设置
                      │          ├ [4] 音频设备设置
                      │          ├ [5] 空占位 h=144 ← 尾部空白（可压扁）
                      │          └ [6] 空占位 h=96  ← 尾部空白（可压扁）
                      └ [本模块的控制区]
```

官方耳机图的 ImageView：`id=bt_heaset_imageView`

### 3.4 弹窗祖先链

```
^0 COUIPanelContentLayout        id=coui_panel_content_layout
^1 FrameLayout                   id=first_panel_container
^2 FrameLayout                   id=bottom_sheet_dialog
^3 COUIPanelPercentFrameLayout   id=design_bottom_sheet      ← 弹窗本体
^4 CoordinatorLayout             id=coordinator
^5 IgnoreWindowInsetsFrameLayout id=container
```

> `COUIBottomSheetBehavior` 的方法名是 **`setPanelState`** / **`setPanelPeekHeight`** /
> **`setFitToContents`**（**不是** androidx 的 `setState` / `setPeekHeight`）。

---

## 四、实现要点

### 4.1 注入流程

```
Hook BtPreferenceFragment#onCreateView   （返回弹窗根 View）
   └─ chain.proceed()
        └─ host.post { injectIntoContainer(root) }

injectIntoContainer:
   ① 总开关检查（Settings.Global）
   ② 设备判断（设备名 / MAC）
   ③ 单一实例守卫（isAttachedToWindow + 根视图比对）
   ④ 定位 contentLayout → 插入 CoordinatorLayout 之后
   ⑤ 布局后按实测坐标对齐（translationY）
   ⑥ 延后替换耳机图 / 隐藏官方无意义条目
```

### 4.2 视觉：用宿主自己的资源重建

为了外观与官方一致，**全部使用宿主资源**：

| 元素 | 资源 |
|---|---|
| 面板底色 | `id=coui_panel_content_layout` 的背景色（采样，实为 `#F0F1F2`） |
| 降噪图标 | `drawable/{noise_reduction,adaptive_noise,transparent_noise,close_noise}{,_not,_ok}` |
| 选中圆底 | 主题蓝 20% 透明（`color/coui_color_primary_blue` = `#FF0066FF`） |
| 卡片 | 自绘白色圆角 + 轻投影 |
| 开关 | `setThumbTintList` / `setTrackTintList`（ON = 主题蓝，OFF = `#C8C8C8`） |

> ⚠️ **坑**：`color/coui_theme_primary_color` 的实际值是 **纯白 `#FFFFFFFF`**（不是蓝色）。
> 若拿它当主题色，选中态会变白而不可见。本模块显式排除近白色。

### 4.3 设备判断

```java
isTarget = 设备名命中 {YUANDAO, NiceHCK, OriG, EB2S, NHCK, 原道}
        || MAC == 最近一次成功连接的 MAC     // 改名兜底
        || 设置页「强制注入所有设备」打开      // 调试
```

> **关于那个名称列表**：本项目只针对 `YUANDAO OriG in` 开发与实测。
> 列表里的其它关键字来自「原道 = NiceHCK 贴牌、同属 NHCKCTRL 协议族」的经验判断
> （官方 App 包名为 `com.yuandao.nicehck`），属于**兜底而非承诺** ——
> 命中即注入，但**未在对应型号上验证过**。若只想严格限定，把列表删到只剩 `YUANDAO` 即可。

### 4.4 配置通道

模块 App 与宿主**不同 UID**，读不到彼此 SharedPreferences，也不能互写 `Android/data`。
因此通过 **`Settings.Global`** 共享：

| Key | 含义 | 默认 |
|---|---|---|
| `nhck_panel_enabled` | 面板注入总开关 | 开 |
| `nhck_panel_verbose` | 诊断日志 | 关 |
| `nhck_panel_force_target` | 强制注入所有设备 | 关 |

- 宿主**读**：无需权限
- App **写**：需要 `WRITE_SECURE_SETTINGS`（adb 一次性授权）
- 宿主侧带 **5 秒缓存**（`NhckConfig`）

---

## 五、踩过的坑（含修法）

| # | 坑 | 现象 | 正确做法 |
|---|---|---|---|
| 1 | 注入到 Activity 的 `android.R.id.content` | 点控件 = 关面板 | 必须注入**弹窗自己的视图**内 |
| 2 | View 直接加进 `RecyclerView` 的 children | 布局错乱/不显示 | 插到它的**普通 ViewGroup 父级** |
| 3 | 注入类 Hook 用 `ExceptionMode.PASSTHROUGH` | 面板一闪即消失 | 用**保护模式**，且注入推迟到 `post()` |
| 4 | 假设 `onViewCreated` 存在 | Hook 静默失败 | 实测方法名是 **`initView`** / **`onCreateView`** |
| 5 | 按 androidx 命名猜 COUI 方法 | 反射全部失败 | 真实名是 **`setPanelState`** / **`setPanelPeekHeight`** |
| 6 | 用**静态字段**记住 ImageView 做高亮 | 点击后屏幕不刷新 | 用 **`row` 引用 + `setTag` + `getChildAt`** |
| 7 | 把 RecyclerView 内**所有**空容器压扁 | 官方间隔条被删，「已连接」顶到电量下 | 只压扁**最后一个有内容子视图之后**的空占位 |
| 8 | 重复注入（守卫只看 `getParent()!=null`） | 高亮更新到另一套视图；切界面后飘层 | 守卫加 **`isAttachedToWindow()` + 根视图比对** |
| 9 | 拿 `coui_theme_primary_color` 当主题蓝 | 它是**纯白** → 选中态不可见 | 只用确认是蓝色的资源，**排除近白色** |
| 10 | 细长轨道复用实心大圆的浅灰 | 开关 OFF 态在白卡片上"消融" | 细长元素需更高对比度：`#C8C8C8` |
| 11 | **保留侦察期的大量观测 Hook** | `:cards` 打开面板时 **SIGABRT 崩溃**（栈全是 `nterp_helper`），面板完全出不来 | **`RECON=false` 拆除观测 Hook**，只留注入必需 |

### 关于 #11 的说明（最重要的一条）

**侦察 Hook 用完必须拆。** 它们不报错、不影响功能，只是让宿主大量方法
deoptimize 到解释器执行 —— 直到某次系统界面重启，宿主崩在
`DeviceCardWidgetProvider` 上，面板彻底失效。

本模块的 `MyDevicesHookInstaller.RECON` 默认 `false`。
**只有在需要重新侦察时才临时开启**，侦察完请立即关闭。

---

## 六、API 102 要点

```java
// 入口（无 IXposedHookServerInit / IXposedHookPackageInit）
public final class Module extends XposedModule {
    public void onModuleLoaded(ModuleLoadedParam param) {}
    public void onPackageLoaded(PackageLoadedParam param) {}   // getDefaultClassLoader()
    public void onPackageReady(PackageReadyParam param) {}     // getClassLoader() / isFirstPackage
    public void onSystemServerStarting(SystemServerStartingParam param) {}
}

// Hook
module.hook(executable)
      .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)  // ⚠️ 仅观测类 Hook 用
      .intercept(new XposedInterface.Hooker() {
          @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
              List<Object> args = chain.getArgs();     // ★ List<Object>，不是 Object[]
              Object self = chain.getThisObject();
              return chain.proceed();
          }
      });
```

**模块元数据**：`META-INF/xposed/{module.prop, java_init.list, scope.list}`
```
minApiVersion=102
targetApiVersion=102
staticScope=true
autoHotReload=false
```

---

## 七、换版复用（系统升级后如何重新适配）

1. **拉取新版本 APK**
   ```bash
   adb shell pm path com.heytap.mydevices
   adb pull <path> mydevices-new.apk
   ```

2. **解析类/方法/字符串表**（本仓库不含解析工具，可用
   `dexdump` / `baksmali` / `jadx` 或自研脚本）

3. **比对锚点**
   - `com.oplus.mydevices.bluetooth.fragment.BtPreferenceFragment#onCreateView|initView`
   - `id=contentLayout` / `id=recycler_view` / `id=bt_heaset_imageView`
   - 若类名或方法名变化 → 修改 `MyDevicesHookInstaller.TARGETS` 与注入点

4. **临时开启侦察**
   把 `RECON` 改为 `true`，打开一次面板，查看日志中的视图树与类形状；
   **分析完立即改回 `false` 并重新构建**。

5. **验证清单**
   - [ ] 面板内出现控制区，位置正确
   - [ ] 降噪 4 档真实切换（回读 `0x0101` 值变化）
   - [ ] EQ / 功能开关生效
   - [ ] 产品图替换成功
   - [ ] 面板关闭后 SPP 释放（`/proc/net/rfcomm` 为空）
   - [ ] 首次点击即可呼出，无进程崩溃

---

## 八、资源占用（实测）

| 指标 | 值 |
|---|---|
| CPU（空闲） | **0.0 %** |
| 内存（`:cards` PSS） | 90 MB（宿主自身 ~87 MB，本模块 ~2–3 MB） |
| RFCOMM socket | 面板关闭后**无残留** |
| 唤醒锁 | **不持有** |
| 蓝牙活动 | 仅面板打开期间 + 每 10 分钟一次查询（每次 ~8 字节 ×4） |
| 日志 | 上限 2 MB 自动重置 |


---

## 九、「隐藏桌面图标」为什么不能只禁用 LAUNCHER 别名

**现象**：隐藏桌面图标后，LSPosed Manager 里点模块也打不开设置页。

**根因**：`PackageManager#getLaunchIntentForPackage()`（LSPosed Manager、
多数启动器、各类工具都用它来"打开 App"）的查找顺序是：

```
① ACTION_MAIN + CATEGORY_INFO       ← 先找
② ACTION_MAIN + CATEGORY_LAUNCHER   ← ① 落空才用
```

若只声明了一个带 `CATEGORY_LAUNCHER` 的 `activity-alias`，把它禁用后
**两类都落空** → `getLaunchIntentForPackage()` 返回 `null` → 打不开。

**解法**：额外声明一个**常驻启用**的 `CATEGORY_INFO` 别名：

```xml
<activity-alias
    android:name="io.github.nhckmelody.InfoAlias"
    android:enabled="true"
    android:exported="true"
    android:targetActivity="io.github.nhckmelody.SettingsActivity">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.INFO" />
    </intent-filter>
</activity-alias>
```

- 它**不会出现在桌面**（launcher 只收录 `CATEGORY_LAUNCHER`）
- 却让 `getLaunchIntentForPackage()` 能命中 → LSPosed 依然能打开

> ⚠️ **维护提示**：`InfoAlias` 任何时候都不要禁用，否则会把自己锁在门外。