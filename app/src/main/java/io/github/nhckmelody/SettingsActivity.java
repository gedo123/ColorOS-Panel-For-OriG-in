package io.github.nhckmelody;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 模块信息页 / 设置页。
 *
 * <p>所有选项<b>直接显示</b>（不再需要隐藏入口）。开关分两类：</p>
 * <ul>
 *   <li><b>桌面图标</b> —— 通过启用/禁用 {@code LauncherAlias} 实现（本进程内即可生效）</li>
 *   <li><b>模块行为</b>（控制面板总开关 / 诊断日志 / 强制注入）—— 写入
 *       {@code Settings.Global}，由宿主进程读取（见 {@link NhckConfig}）</li>
 * </ul>
 */
public class SettingsActivity extends Activity {

    /** 承载桌面入口的组件别名（禁用即"隐藏桌面图标"）。 */
    private static final String LAUNCHER_ALIAS =
            "io.github.nhckmelody.LauncherAlias";

    private TextView hint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        root.setPadding(p, dp(28), p, dp(28));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextSize(22f);
        title.setTextColor(0xFF1A1A1A);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView ver = new TextView(this);
        ver.setText("版本 " + versionName());
        ver.setTextSize(13f);
        ver.setGravity(Gravity.CENTER);
        ver.setTextColor(0xFF757575);
        ver.setPadding(0, dp(4), 0, dp(18));
        root.addView(ver);

        // ---- 说明 ----
        root.addView(section("这是什么"));
        root.addView(body("把原道 / NiceHCK 等第三方蓝牙耳机接入 ColorOS「无线耳机」面板，"
                + "在系统面板内直接提供降噪、均衡器与功能开关。"));

        root.addView(section("工作方式"));
        root.addView(body("· 通过 LSPosed（API 102）注入系统「设备空间」进程\n"
                + "· 作用域：com.heytap.mydevices、com.oplus.melody\n"
                + "· 只有当面板对应的设备是原道 / NiceHCK 系列时才注入控制区\n"
                + "· 通过蓝牙 SPP 通道与耳机直接通信，不依赖官方 App"));

        root.addView(section("使用提示"));
        root.addView(body("打开方式：下拉通知栏 → 长按蓝牙卡片 → 点你的耳机。\n"
                + "控制区出现在面板底部，位置随官方内容自动对齐。"));

        // ---- 设置 ----
        root.addView(section("设置"));

        // ① 隐藏桌面图标（本进程内生效，无需额外权限）
        root.addView(mkLauncherSwitch());

        // ② 模块行为开关（写入 Settings.Global，宿主读取）
        root.addView(body("以下开关写入系统设置，宿主最多 5 秒后生效。修改需要一次性授权。"));
        root.addView(mkGlobalSwitch(NhckConfig.KEY_ENABLED, "启用控制面板", true,
                "关闭后不再向系统面板注入控制区"));
        root.addView(mkGlobalSwitch(NhckConfig.KEY_VERBOSE, "输出诊断日志", false,
                "开启后写详细日志（视图树 / 定位 / 刷新追踪）"));
        root.addView(mkGlobalSwitch(NhckConfig.KEY_FORCE_TARGET, "强制注入所有设备", false,
                "调试用：跳过设备判断，对任何蓝牙设备都注入"));

        hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setTextColor(0xFFB00020);
        hint.setPadding(0, dp(12), 0, 0);
        hint.setVisibility(View.GONE);
        root.addView(hint);

        TextView adb = new TextView(this);
        adb.setTextSize(11f);
        adb.setTextColor(0xFF757575);
        adb.setTextIsSelectable(true);
        adb.setPadding(0, dp(10), 0, 0);
        adb.setText("若提示无权限，在电脑上执行一次：\n"
                + "adb shell su -c \"pm grant " + getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS\"");
        root.addView(adb);

        return sv;
    }

    /**
     * 「隐藏桌面图标」开关。
     *
     * <p>原理：桌面入口由 {@code activity-alias} 承载，禁用该别名即可隐藏图标；
     * 而 {@code SettingsActivity} 本身仍保持可用，因此
     * <b>LSPosed Manager 与 adb 依然能打开本页面</b>，不会把自己锁在门外。</p>
     */
    private View mkLauncherSwitch() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(6));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(this);
        tv.setText("隐藏桌面图标");
        tv.setTextSize(15f);
        row.addView(tv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(this);
        sw.setChecked(!isLauncherEnabled());
        style(sw);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                setLauncherVisible(!checked);
            }
        });
        row.addView(sw);
        box.addView(row);

        TextView d = new TextView(this);
        d.setText("隐藏后仍可通过 LSPosed Manager 打开本页面；\n"
                + "也可用 adb 打开：\n"
                + "adb shell am start -n " + getPackageName() + "/.SettingsActivity");
        d.setTextSize(12f);
        d.setTextColor(0xFF8A8A8A);
        d.setLineSpacing(dp(2), 1f);
        box.addView(d);
        return box;
    }

    // ------------------------------------------------------------------
    // 桌面图标显隐
    // ------------------------------------------------------------------

    private ComponentName aliasComponent() {
        return new ComponentName(getPackageName(), LAUNCHER_ALIAS);
    }

    private boolean isLauncherEnabled() {
        try {
            int state = getPackageManager().getComponentEnabledSetting(aliasComponent());
            // 未设置过 = 默认启用（清单里 android:enabled="true"）
            return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return true;
        }
    }

    private void setLauncherVisible(boolean visible) {
        try {
            getPackageManager().setComponentEnabledSetting(
                    aliasComponent(),
                    visible ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            Toast.makeText(this,
                    visible ? "桌面图标已显示" : "桌面图标已隐藏",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "修改失败: " + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------
    // Settings.Global 开关
    // ------------------------------------------------------------------

    private View mkGlobalSwitch(final String key, String label, boolean def, String desc) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(6));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(15f);
        row.addView(tv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(this);
        sw.setChecked("1".equals(readGlobal(key, def ? "1" : "0")));
        style(sw);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                writeGlobal(key, checked ? "1" : "0");
            }
        });
        row.addView(sw);
        box.addView(row);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12f);
        d.setTextColor(0xFF8A8A8A);
        box.addView(d);
        return box;
    }

    /** 统一开关配色（避免系统强调色为白时看不见）。 */
    private void style(Switch sw) {
        try {
            sw.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFF0066FF));
            sw.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][]{ new int[]{android.R.attr.state_checked}, new int[]{} },
                    new int[]{0x400066FF, 0xFFC8C8C8}));
        } catch (Throwable ignored) {
        }
    }

    private String readGlobal(String key, String def) {
        try {
            String v = Settings.Global.getString(getContentResolver(), key);
            return (v != null) ? v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private void writeGlobal(String key, String value) {
        try {
            Settings.Global.putString(getContentResolver(), key, value);
            Toast.makeText(this, "已保存（宿主最多 5 秒后生效）", Toast.LENGTH_SHORT).show();
            if (hint != null) hint.setVisibility(View.GONE);
        } catch (Throwable t) {
            if (hint != null) {
                hint.setText(getString(R.string.grant_needed)
                        + "\n（" + t.getClass().getSimpleName() + "）");
                hint.setVisibility(View.VISIBLE);
            }
            Toast.makeText(this, "无写入权限，请先授权", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private TextView section(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(0xFF0066FF);
        tv.setPadding(0, dp(18), 0, dp(6));
        return tv;
    }

    private TextView body(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setTextColor(0xFF333333);
        tv.setLineSpacing(dp(3), 1f);
        return tv;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
