# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 风格。

## [1.0.1] - 2026-10-07

修复「隐藏桌面图标后，LSPosed Manager 里打不开设置页」的问题。

### 修复
- **隐藏桌面图标后仍可从 LSPosed 打开设置页**
  LSPosed Manager 通过 `PackageManager#getLaunchIntentForPackage()` 打开模块，
  该方法先找 `ACTION_MAIN + CATEGORY_INFO`，落空才找 `CATEGORY_LAUNCHER`。
  原来只声明了一个带 `LAUNCHER` 的 `activity-alias`，隐藏图标时把它禁用后
  **两类都落空** → 返回 `null` → 打不开。
  现新增常驻启用的 `InfoAlias`（`ACTION_MAIN` + `CATEGORY_INFO` + `CATEGORY_DEFAULT`），
  它不出现在桌面，但能让 `getLaunchIntentForPackage()` 命中。
- **兼容带 `MATCH_DEFAULT_ONLY` 的查询**
  补上 `CATEGORY_DEFAULT`，使 `am start` 隐式意图等工具也能解析到该入口。

> 已在真机验证三条启动路径（隐式意图 / 显式组件 / 按解析结果启动），
> 且桌面图标处于隐藏状态（`disabledComponents: LauncherAlias`）。

## [1.0.0] - 2026-10-07

首个公开版本。**单机型示范项目** —— 只针对 `YUANDAO OriG in` 一款耳机。

### 新增
- **系统面板注入**：将控制区注入 ColorOS「无线耳机」面板（`com.heytap.mydevices`）内部，
  位置随官方内容自动对齐，不额外抬高官方面板。
- **降噪控制**：4 档（深度 / 降噪 / 关闭 / 通透），点击后真实下发并回读确认。
- **均衡器**：5 项（均衡中正 / 欧美澎湃 / 真律还原 / 细腻佳音 / 温婉人声）。
- **功能开关**：游戏模式 / 入耳检测 / 双设备连接 / 抗风噪。
- **真实数据回读**：降噪档位、EQ、固件版本；打开面板立即同步一次 + 可见期间每 10 分钟自动同步。
- **设备判断**：仅当面板对应设备为 `YUANDAO OriG in` 时注入；支持按 MAC 兜底。
- **设备图替换**：用原道 OriG in 产品图替换系统通用耳机图。
- **官方条目清理**：隐藏无意义的「通用设置」/「音频设备设置」入口。
- **设置页**：隐藏桌面图标 / 启用控制面板 / 输出诊断日志 / 强制注入所有设备。
  通过 `Settings.Global` 与宿主进程共享配置。

### 技术要点
- 完全基于 **LSPosed API 102（Modern API）**：`XposedModule` + `hook().intercept(Hooker)` + `Chain`，
  未使用 `XC_MethodHook` / `XposedHelpers`。
- 与耳机通过**经典蓝牙 SPP（RFCOMM）**通信，帧格式与指令集见 `docs/ARCHITECTURE.md`。
- 面板关闭即释放 SPP，不占用耳机通道。

### 已知限制
- **只适配 `YUANDAO OriG in`**（固件 4.08）。代码内有按名称匹配的兜底，但其它型号均未验证。
- 控制区与官方面板各自滚动（结构限制）。
- 仅在一台设备（OnePlus PJF110 / ColorOS 16.0.1.301(CN01) / Android 16）上实测。
- 依赖宿主 App 内部结构，系统升级后可能失效。
