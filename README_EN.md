<p align="center">
  <a href="README.md">简体中文</a> · <b>English</b>
</p>

<p align="center">
  <img src="docs/banner.jpg" alt="ColorOS-Panel For OriG in">
</p>

# ColorOS-Panel For OriG in

An LSPosed module that brings the **YUANDAO OriG in** earbuds into the
**ColorOS "Wireless Earphones" system panel** — providing **ANC levels, an equalizer
and feature toggles** right inside the stock panel, with **real battery and state read-back**.

> **Scope**: this is a **single-model demo project** — it targets **one** pair of earbuds,
> the **YUANDAO OriG in**, to demonstrate *how to integrate a third-party headset
> into the ColorOS system panel*. The earbuds were purchased by the author;
> there is **no affiliation** with the vendor.
>
> Built on **LSPosed API 102 (Modern API)** — no legacy `XC_MethodHook` / `XposedHelpers`.

---

## ✨ Screenshot

<p align="center">
  <img src="docs/screenshot-panel.jpg" width="340" alt="Third-party controls inside the ColorOS Wireless Earphones panel">
  <br>
  <sub>ColorOS 16 "Wireless Earphones" panel — the third-party controls are injected directly into it</sub>
</p>

Top to bottom: **official product image** (replacing the generic headset graphic)
→ device name and **real battery level** → connection state → **live status summary**
(ANC level / EQ / firmware) → **4 ANC levels** → **5 EQ presets** → **feature toggles**.

| Capability | Description |
|---|---|
| **Panel injection** | Controls are injected inside the system bottom sheet, auto-aligned with the official content |
| **ANC** | 4 levels: Deep / Noise Cancelling / Off / Transparency |
| **Equalizer** | 5 presets: Balanced / Bass Boost / Pure / Fine / Vocal |
| **Feature toggles** | Game Mode / In-ear Detection / Dual Connection / Wind Noise Reduction |
| **Live data** | ANC level, EQ and firmware are read back on panel open, then re-synced every 10 minutes |
| **Device image** | The official product photo replaces the generic headset graphic |
| **Styling** | Matches the stock look: light-grey panel, white rounded cards, pale-blue selection, theme-blue switches |

---

## 📱 Requirements

| Item | Requirement |
|---|---|
| **OS** | **ColorOS 16** (tested `16.0.1.301(CN01)`), Android 16 (API 36) |
| **Framework** | **LSPosed** with **API 102** support |
| **Host app**<br>(panel lives here) | `com.heytap.mydevices` (My Devices / 设备空间) **16.8.5** (versionCode 1608005) |
| **Other scope entry** | `com.oplus.melody` (Wireless Earphones / 无线耳机) **16.10.1** (versionCode 16010001, full string `16.10.1_ba899f8_260905`)<br><sub>On the tested device the panel actually lives in `com.heytap.mydevices`; this package is kept in scope for other models/versions</sub> |
| **Root** | **Required** — LSPosed itself needs root. The extra adb grant is **only for the settings-page switches** and is unrelated to module functionality (see [Install](#-install)) |
| **Earbuds** | **Only `YUANDAO OriG in` has been tested** (firmware 4.08). Transport is classic Bluetooth SPP (UUID suffix = ASCII `NHCKCTRL`)<br><sub>The code keeps a device-name matching fallback (see [Usage](#-usage)), but **no other model has been verified**</sub> |

> ⚠️ **Compatibility warning**: this module depends on the host app's **internal classes and
> method signatures**. A system or "My Devices" app update **may break it**, requiring
> re-adaptation (see the "Re-adapting to a new version" section of
> [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)).

---

## 🚀 Install

> ### ⚠️ Read this first: **root + LSPosed are required**
>
> The panel injection capability comes **entirely from LSPosed** — it injects this module's
> code into the system "My Devices" process so it can reuse the host's Bluetooth permissions
> to control the earbuds.
>
> **adb is not a substitute for root.** Step 4 below (the adb grant) is **optional**;
> it **only affects whether 3 switches on the settings page can be changed**, and has
> nothing to do with whether the module works. Skip it and everything still works.

### Required steps

1. **Install the APK** (download from [Releases](../../releases))
   ```bash
   adb install -r ColorOS-Panel-For-OriG-in-v1.0.0.apk
   ```

2. **Enable the module in LSPosed Manager** and select the scope:
   ```
   com.heytap.mydevices
   com.oplus.melody
   ```

3. **Reboot** (or restart the processes of the apps above)

   > At this point the module is **fully functional**: panel injection, ANC, EQ,
   > toggles and battery read-back are all active.

### Optional step (advanced, unrelated to functionality)

4. **Grant the settings-page switches** (safe to skip)

   Three switches on the settings page (Enable panel / Verbose log / Force-inject all devices)
   need to write to `Settings.Global`. A one-time adb grant is enough (it persists across reboots):
   ```bash
   adb shell su -c "pm grant io.github.nhckmelody android.permission.WRITE_SECURE_SETTINGS"
   ```

   **Why is this needed?**
   - The injected code runs in the **host process** (`com.heytap.mydevices`), while the
     settings page runs in the **module's own process** (`io.github.nhckmelody`) —
     they have **different UIDs**
   - So they cannot read each other's `SharedPreferences`, nor write into each other's
     `/data/data/`
   - Configuration therefore travels through **`Settings.Global`**: the host can **read**
     it with no permission at all, but the module app needs `WRITE_SECURE_SETTINGS` to **write**
   - That permission is `signature|privileged` — **a normal app cannot request it via a
     dialog**; only adb/root can grant it (adbd itself does not hold it, hence `su -c`)

   > **Without the grant**: the defaults apply — **Enable panel = ON**, verbose log = off,
   > force-inject = off, which is exactly the recommended daily configuration.
   > The three switches simply cannot be changed from the page, and the page shows the
   > command above.

   > **Note**: the `Hide launcher icon` switch does **not** need this grant — it only
   > changes this app's own component state (`PackageManager.setComponentEnabledSetting`).

---

## 🎧 Usage

```
Pull down the notification shade → long-press the Bluetooth card → tap your earbuds
```

The controls appear at the **bottom** of the system panel, automatically aligned with
the official content.

> **Note**: the controls are only injected when the panel's device is the **YUANDAO OriG in**
> (so we don't insert widgets into car kits or other brands' headsets). The decision is based
> on the device name, with "the last successfully connected MAC" as a fallback — so
> **renaming the device still works**.
>
> The name-matching list in code is `{YUANDAO, NiceHCK, OriG, EB2S, NHCK, 原道}`.
> It is an **empirical fallback for the same protocol family** (YUANDAO is a NiceHCK OEM;
> the official app's package is `com.yuandao.nicehck`). **Nothing beyond `YUANDAO OriG in`
> has been tested** — if your model is recognised, feedback is welcome.

---

## ⚙️ Settings page

Launcher icon "**ColorOS-Panel For OriG in**", or open it from LSPosed Manager.

| Switch | Effect | Needs adb grant? |
|---|---|:---:|
| **Hide launcher icon** | Still reachable from LSPosed Manager; or `adb shell am start -n io.github.nhckmelody/.SettingsActivity` | ❌ No |
| **Enable panel** | Master switch; when off, nothing is injected (default **on**) | ✅ Yes |
| **Verbose log** | Writes detailed logs (view tree / positioning / refresh tracing) (default off) | ✅ Yes |
| **Force-inject all devices** | Debug only: skips the device check (default off) | ✅ Yes |

> **Why the last three need the grant**: the module app and the host have **different UIDs**,
> so the switches are shared through `Settings.Global` (host reads need no permission,
> app writes need a system-level one). See [Install · optional step](#-install).
> Changes take effect within **5 seconds**.

---

## 🔧 Build

Requires **JDK 17** (AGP 9.x hangs on JDK 25) and **Android SDK 36**.

```bash
# 1. point at your SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. build
./gradlew :app:assembleDebug        # output: app/build/outputs/apk/debug/app-debug.apk
```

### About release signing

`assembleDebug` output is signed with Gradle's auto-generated **debug key** and carries
`android:debuggable` — fine for local testing, **not for distribution**. To hand the APK to
others you must sign it with **your own keystore**.

Create `keystore.properties` in the project root (already excluded by `.gitignore`):

```properties
# ⚠️ Use FORWARD SLASHES: backslash is an escape character in .properties,
#    so D:\a\b silently becomes Dab
storeFile=D:/path/to/release.jks
storePassword=your-password
keyAlias=nhck
keyPassword=your-password
```

Then `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
**Without that file**, `assembleRelease` only produces an **unsigned** APK, which cannot be installed.

> **⚠️ Back up the keystore forever.** Android only allows in-place updates of APKs with the
> **same package name + same signature**. Losing the key means: you can no longer ship
> over-the-installable updates, and existing users must uninstall and reinstall (losing settings).
> Already-published versions keep working.

Useful commands:

```bash
keytool -genkeypair -v -keystore release.jks -alias nhck \
        -keyalg RSA -keysize 2048 -validity 10000

# verify the output
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

---

## 🩺 Troubleshooting

**Log location**
```
/sdcard/Android/data/com.heytap.mydevices/files/nhck-wl-dump.log
```

**Enable verbose logging** (two ways)

1. Settings page → "Verbose log"
2. Or create a marker file (**no rebuild required**):
   ```bash
   adb shell su -c 'touch /sdcard/Android/data/com.heytap.mydevices/files/nhck-verbose'
   ```
   Delete the file to turn it off again.

**The log is capped**: it resets automatically past 2 MB, so it never grows without bound.

**Re-reconnaissance** (when a system update breaks the module)
Set `MyDevicesHookInstaller.RECON` to `true` to dump class/method shapes again and compare
for anchor drift. **Keep it `false` normally** — a large number of observation hooks
deoptimizes host methods and was measured to **crash the `:cards` process**.

**Helper tool**: `tools/parse_apk.py` is a zero-dependency APK/DEX structure extractor,
used to dump a new method table after a system update and diff the anchors.
See [tools/README.md](tools/README.md).

---

## ⚠️ Known limitations

1. **One earbud model only** — developed and tested against `YUANDAO OriG in` (firmware 4.08)
   only. The name-matching fallback exists, but **no other model has been verified** — do not
   assume it works.
2. **Independent scrolling** — structurally our controls are a **sibling** of the official
   `RecyclerView`, not part of its scroll stream. A capped-height scroll area mitigates this;
   daily use is fine. A proper fix requires restructuring the host view tree (higher risk,
   not adopted).
3. **Single test environment** — one device only: OnePlus PJF110 / ColorOS 16.0.1.301(CN01) / Android 16.
4. **Depends on host internals** — a system or "My Devices" update may break it.
5. **Only one process may hold the SPP channel** — while the panel is open it holds the
   earbuds' SPP channel, so the **official app cannot connect** (released as soon as the
   panel closes).

---

## 📄 Third-party assets

- This module contains **no** code or resources from third-party apps, with one exception:
  **`app/src/main/assets/nhck_origin.png`** — the YUANDAO OriG in product image, taken from
  the official app's (`com.yuandao.nicehck`) assets, **used solely to identify the device
  model in the system panel**. **Copyright remains with the rights holder and this file is
  not covered by this project's MIT licence.** See [NOTICE](NOTICE).
- If the rights holder objects, please open an issue and I will **remove it immediately**.

---

## ⚖️ Disclaimer

- This is an **unofficial** project. It is **not affiliated with, authorised by or endorsed by**
  **OPPO / OnePlus / ColorOS / YUANDAO (NiceHCK)** or their affiliates.
- The earbuds were purchased by the author. This project **does not distribute any vendor
  software or firmware** in any form.
- Provided for **study and research** only. Using it may violate warranty terms or service
  agreements — **use at your own risk**.
- The Bluetooth logo is a registered trademark of **Bluetooth SIG, Inc.**

---

## 🙏 Credits

The protocol research and injection approach benefited from these open-source projects
(**the code here is an independent implementation**):

- **ZaeXT/NiceHCK_Controller** — research into this family's headset control protocol
- **Andrea-lyz/MelodyCodecTweaker** — approaches to LSPosed hooking and panel UI injection

And the framework capabilities provided by **LSPosed** and the **libxposed API**.

---

## 📜 Licence

[MIT](LICENSE) — covering this project's source code only; third-party assets are excluded
(see [NOTICE](NOTICE)).
