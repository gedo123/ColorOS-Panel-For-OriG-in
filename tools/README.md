# tools —— 辅助脚本

配合 [docs/ARCHITECTURE.md 第七节「换版复用」](../docs/ARCHITECTURE.md) 使用。

## `parse_apk.py`

**零依赖**（仅用 Python 标准库）的 APK/DEX 结构提取器。

```bash
python parse_apk.py /path/to/base.apk
```

输出（与 APK 同名的 `.txt`）：
- 类名列表
- 方法与签名
- 字符串常量

**用途**：系统或宿主 App 升级后，用新版 APK 重新导出方法表，与旧版对比即可
快速发现**锚点漂移**（类名/方法名变化），再据此调整
`MyDevicesHookInstaller.TARGETS` 与注入点。

## `parse-dump.py`

解析模块写出的白名单侦察 dump（`wl-dump.log`）。

```bash
python parse-dump.py wl-dump.log
```

**用途**：只在临时开启 `RECON` 做侦察时才会用到。

> ⚠️ 注意：开启 `RECON` 会安装大量观测 Hook，导致宿主方法 deoptimize，
> **实测可致 `:cards` 进程崩溃**。侦察完请立即把 `RECON` 改回 `false` 并重新构建。
