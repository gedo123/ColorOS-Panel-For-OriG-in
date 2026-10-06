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
 * <p>所有选项直接显示。开关分两类：</p>
 * <ul>
 *   <li><b>隐藏桌面图标</b> —— 启用/禁用 {@code LauncherAlias}（本进程内生效）</li>
 *   <li><b>模块行为</b>（控制面板总开关 / 诊断日志 / 强制注入）—— 写入
 *       {@code Settings.Global}，由宿主进程读取（见 {@link NhckConfig}）</li>
 * </ul>
 *
 * <p>说明：应用图标本身即桌面图标，此页面不再重复显示大图，
 * 以免受部分 ROM 的测量/裁剪行为影响（本机实测该 ROM 对程序化图片视图
 * 只提供约 123dp 的可绘制高度，无法可靠展示大图）。</p>
 */
public class SettingsActivity extends Activity {

    /** 承载桌面入口的组件别名（禁用即"隐藏桌面图标"）。 */
    private static final String LAUNCHER_ALIAS = "io.github.nhckmelody.LauncherAlias";

    private TextView hint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 用 XML 布局（标准路径）：顶部图标是固定 160dp 的 ImageView，fitCenter
        setContentView(R.layout.activity_settings);
        LinearLayout root = findViewById(R.id.root);
        buildUi(root);
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    /** 把页面内容添加到 XML 布局里的 root 容器。 */
    private void buildUi(LinearLayout root) {
        // 图标：点击放大查看原图
        try {
            View iconView = findViewById(R.id.icon);
            if (iconView != null) {
                iconView.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showFullIcon();
                    }
                });
            }
        } catch (Throwable ignored) {
            // 图标只是装饰，失败不影响其它内容
        }

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

        root.addView(section("这是什么"));
        root.addView(body("把「原道 OriG in」蓝牙耳机接入 ColorOS「无线耳机」面板，"
                + "在系统面板内直接提供降噪、均衡器与功能开关。\n"
                + "（本模块为单机型示范项目，仅在该型号上实测。）"));

        root.addView(section("工作方式"));
        root.addView(body("· 通过 LSPosed（API 102）注入系统「设备空间」进程\n"
                + "· 作用域：com.heytap.mydevices、com.oplus.melody\n"
                + "· 仅当面板对应的设备为「原道 OriG in」时才注入控制区\n"
                + "· 通过蓝牙 SPP 通道与耳机直接通信，不依赖官方 App"));

        root.addView(section("使用提示"));
        root.addView(body("打开方式：下拉通知栏 → 长按蓝牙卡片 → 点你的耳机。\n"
                + "控制区出现在面板底部，位置随官方内容自动对齐。"));

        root.addView(section("设置"));

        // ① 隐藏桌面图标（本进程内生效，不需要额外权限）
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
    }

    /**
     * 点击图标：放大显示**原图**（未缩放的完整画作）。
     *
     * <p>原图作为 {@code drawable-nodpi/nhck_icon_full.png} 打进包里
     * （nodpi 保证不被系统按密度缩放）。</p>
     */
    private void showFullIcon() {
        try {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageResource(R.drawable.nhck_icon_full);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            iv.setAdjustViewBounds(true);

            android.widget.FrameLayout box = new android.widget.FrameLayout(this);
            int pad = dp(6);
            box.setPadding(pad, pad, pad, pad);
            box.addView(iv, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

            final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.app_name))
                    .setView(box)
                    .setPositiveButton("关闭", null)
                    .create();
            dlg.show();
            try {
                int side = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
                dlg.getWindow().setLayout(side, ViewGroup.LayoutParams.WRAP_CONTENT);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开原图: " + t.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 「隐藏桌面图标」开关。
     *
     * <p>桌面入口由 {@code activity-alias} 承载，禁用别名即可隐藏图标；
     * 而 {@code SettingsActivity} 本身仍可用，因此
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
            Toast.makeText(this, visible ? "桌面图标已显示" : "桌面图标已隐藏",
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

    /**
     * 统一开关配色。
     *
     * <p>配色要点（踩过坑）：</p>
     * <ul>
     *   <li>不能用系统默认色 —— 该 ROM 的主题强调色可能是**白色**，
     *       开启态在白卡片上会"消失"</li>
     *   <li>也不能**两种状态都用蓝色滑块** —— 那样关/开只差滑块位置，
     *       肉眼几乎分不出（曾因此误判所有开关都是开启的）。
     *       必须让**颜色也随状态变化**：关=白滑块+深灰底槽，开=蓝滑块+浅蓝底槽</li>
     * </ul>
     */
    private void style(Switch sw) {
        try {
            sw.setThumbTintList(new android.content.res.ColorStateList(
                    new int[][]{ new int[]{android.R.attr.state_checked}, new int[]{} },
                    new int[]{0xFF0066FF, 0xFFFFFFFF}));
            sw.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][]{ new int[]{android.R.attr.state_checked}, new int[]{} },
                    new int[]{0x400066FF, 0xFFB0B0B0}));
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
