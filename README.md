# NiceHCK ColorOS Panel

把**第三方蓝牙耳机**（原道 / NiceHCK 等）接入 **ColorOS「无线耳机」系统面板**的 LSPosed 模块
—— 在系统自带的耳机面板里直接提供**降噪档位、均衡器与功能开关**，并回读**真实电量与状态**。

> 基于 **LSPosed API 102（Modern API）**，未使用任何旧版 `XC_MethodHook` / `XposedHelpers`。

---

## ✨ 效果

| 能力 | 说明 |
|---|---|
| **面板嵌入** | 控制区注入系统弹窗内部，位置随官方内容自动对齐 |
| **降噪切换** | 4 档：深度 / 降噪 / 关闭 / 通透 |
| **均衡器** | 5 项：均衡中正 / 欧美澎湃 / 真律还原 / 细腻佳音 / 温婉人声 |
| **功能开关** | 游戏模式 / 入耳检测 / 双设备连接 / 抗风噪 |
| **真实数据** | 降噪档位、EQ、固件版本实时回读（打开面板即刷新 + 每 10 分钟自动同步） |
| **设备图** | 用原道官方产品图替换系统通用耳机图 |
| **视觉** | 官方同款：浅灰面板底 + 白色圆角卡片 + 淡蓝选中态 + 主题蓝开关 |

<p align="center">
  <img src="docs/icon.png" width="140" alt="图标">
</p>

---

## 📱 环境要求

| 项 | 要求 |
|---|---|
| **系统** | **ColorOS 16**（实测 `16.0.1.301(CN01)`）、Android 16（API 36） |
| **框架** | **LSPosed**，支持 **API 102** |
| **宿主 App** | `com.heytap.mydevices`（设备空间）**16.8.5** |
| **Root** | 非必需（LSPosed 需要）；仅「设置页开关」的写入权限需要一次 adb 授权 |
| **耳机** | 采用 **NHCKCTRL** 协议的原道 / NiceHCK 系列（实测 `YUANDAO OriG in`，固件 4.08） |

> ⚠️ **兼容性警告**：本模块依赖宿主 App 的**内部类与方法结构**。系统或「设备空间」App 升级后
> **可能失效**，需要重新适配（见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 的「换版复用」章节）。

---

## 🚀 安装

1. **安装 APK**（从 [Releases](../../releases) 下载）
   ```bash
   adb install -r NiceHCK-ColorOS-Panel.apk
   ```

2. **在 LSPosed Manager 中启用模块**，并勾选作用域：
   ```
   com.heytap.mydevices
   com.oplus.melody
   ```

3. **重启手机**（或重启上述 App 的进程）

4. **（可选）授权设置页开关**
   设置页里的开关需要写入系统设置，用一次 adb 授权即可（永久生效）：
   ```bash
   adb shell su -c "pm grant io.github.nhckmelody android.permission.WRITE_SECURE_SETTINGS"
   ```
   > 未授权也能正常使用面板，只是**设置页的开关变只读**，页面会显示这条命令。

---

## 🎧 使用

```
下拉通知栏 → 长按蓝牙卡片 → 点你的耳机
```

控制区会出现在系统面板的**底部**，位置自动对齐官方内容。

> **提示**：只有当面板对应的设备是**原道 / NiceHCK 系列**时才会注入控制区
> （避免给车机、其它品牌耳机凭空插入控件）。判断依据是设备名，
> 并用「最近一次成功连接的 MAC」兜底 —— 所以**改了设备名也能识别**。

---

## ⚙️ 设置页

桌面图标「**NiceHCK 耳机面板**」，或从 LSPosed Manager 打开。

| 开关 | 作用 |
|---|---|
| **隐藏桌面图标** | 隐藏后仍可从 LSPosed Manager 打开；也可用 `adb shell am start -n io.github.nhckmelody/.SettingsActivity` |
| **启用控制面板** | 总开关，关闭后不再注入控制区 |
| **输出诊断日志** | 输出视图树 / 定位 / 刷新追踪等详细日志 |
| **强制注入所有设备** | 调试用：跳过设备判断 |

> 模块 App 与宿主是**不同 UID**，因此开关通过 `Settings.Global` 共享
> （宿主读取无需权限），修改后**最多 5 秒生效**。

---

## 🔧 构建

需要 **JDK 17**（AGP 9.x 在 JDK 25 下会挂起）与 **Android SDK 36**。

```bash
# 1. 配置 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. 构建
./gradlew :app:assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
```

**发布签名（可选）**：在项目根目录创建 `keystore.properties`（已被 `.gitignore` 排除）：

```properties
storeFile=../nhck-release.jks
storePassword=******
keyAlias=nhck
keyPassword=******
```

然后 `./gradlew :app:assembleRelease`。

---

## 🩺 排障

**日志位置**
```
/sdcard/Android/data/com.heytap.mydevices/files/nhck-wl-dump.log
```

**开启详细日志**（两种方式）

1. 设置页 → 「输出诊断日志」
2. 或创建标记文件（**不需要重新编译**）：
   ```bash
   adb shell su -c 'touch /sdcard/Android/data/com.heytap.mydevices/files/nhck-verbose'
   ```
   删除该文件即关闭。

**日志有大小上限**：超过 2 MB 会自动重置，不会无限增长。

**重新侦察**（系统升级导致失效时）
源码中 `MyDevicesHookInstaller.RECON` 改为 `true` 可重新输出类/方法结构，
用于比对锚点漂移。**平时请保持 `false`** —— 大量观测 Hook 会让宿主方法
deoptimize，实测会导致 `:cards` 进程崩溃。

**辅助工具**：`tools/parse_apk.py` 是零依赖的 APK/DEX 结构提取器，
用于系统升级后**导出新方法表并比对锚点**。详见 [tools/README.md](tools/README.md)。

---

## ⚠️ 已知限制

1. **与官方面板各自滚动** —— 我们的控制区在结构上是官方 `RecyclerView` 的**兄弟节点**，
   不在它的滚动流里。当前用「限高滚动」缓解，日常无碍。根治需要重构宿主视图层级（风险较高，暂未采用）。
2. **仅一台设备、一款耳机实测** —— OnePlus PJF110 / ColorOS 16.0.1.301 / `YUANDAO OriG in` 固件 4.08。
3. **依赖宿主内部结构** —— 系统或「设备空间」升级后可能失效。
4. **同时只允许一个程序占用 SPP** —— 面板打开期间会占用耳机 SPP 通道，
   **官方 NiceHCK App 此时无法连接**（面板关闭后立即释放）。

---

## 📄 第三方素材声明

- 本模块**不包含**任何第三方 App 的代码或资源文件，除下列一项：
  **`app/src/main/assets/nhck_origin.png`** —— 原道耳机产品图，取自官方 App
  （`com.yuandao.nicehck`）的 assets，**仅用于在系统面板中标识设备型号**。
  **版权归原权利人所有，不在本项目的 MIT 许可范围内。** 详见 [NOTICE](NOTICE)。
- 如权利人认为不妥，请开 Issue，我会**立即移除**该文件。

---

## ⚖️ 免责声明

- 本项目为**非官方**作品，与 **OPPO / 一加 / ColorOS / 原道 / NiceHCK** 及其关联公司
  **无任何关联**，未被其授权或认可。
- 仅供**学习与研究**使用。使用本模块可能违反设备保修条款或相关服务协议，**风险自负**。
- 图标中的 Bluetooth 标志为 **Bluetooth SIG, Inc.** 的注册商标。

---

## 🙏 致谢

本项目的协议调研与注入思路受益于以下开源项目（**代码为独立实现**）：

- **ZaeXT/NiceHCK_Controller** —— NiceHCK 耳机控制协议的调研
- **Andrea-lyz/MelodyCodecTweaker** —— LSPosed Hook 与面板 UI 注入的方法思路

以及 **LSPosed** 与 **libxposed API** 提供的框架能力。

---

## 📜 许可证

[MIT](LICENSE) —— 仅覆盖本项目源代码；第三方素材除外（见 [NOTICE](NOTICE)）。
