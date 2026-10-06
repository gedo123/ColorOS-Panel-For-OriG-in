package io.github.nhckmelody;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 官方风格控制面板（方案 A：复用宿主 UI 资源重建控件）。
 *
 * <p><b>诚实说明</b>：这是<b>仿制</b>，不是系统为官方耳机生成的控件。
 * 素材（图标/底图）取自宿主自己的资源，因此外观与官方一致；
 * 数据与操作走我们已验证的 NiceHCK SPP 协议。</p>
 *
 * <p>宿主资源（mydevices 16.8.5 实测存在）：
 * <pre>
 * drawable/adaptive_noise{,_not,_ok}
 * drawable/transparent_noise{,_not,_ok}
 * drawable/noise_reduction{,_not,_ok}
 * drawable/close_noise{,_not,_ok}
 * drawable/noise_control_bg / noise_button_selected_bg / noise_button_normal_bg
 * drawable/bt_connect_icon / bt_logo_icon
 * </pre></p>
 */
final class OfficialStylePanel {

    private static final String HOST_PKG = "com.heytap.mydevices";

    private OfficialStylePanel() {}

    // ------------------------------------------------------------------
    // 宿主资源解析
    // ------------------------------------------------------------------

    /**
     * 资源名 → ID 的缓存。
     *
     * <p>{@code Resources.getIdentifier()} 是按名字查资源表，<b>官方明确说明它较慢</b>；
     * 而 {@code drawable()}/{@code color()} 在每次状态刷新（降噪/EQ/开关）时都会被调用，
     * 因此必须缓存，否则每次点击都要做多次资源表查找。</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> ID_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static int resId(Context ctx, String name, String type) {
        String key = type + '/' + name;
        Integer hit = ID_CACHE.get(key);
        if (hit != null) return hit;
        int id = 0;
        try {
            id = ctx.getResources().getIdentifier(name, type, HOST_PKG);
        } catch (Throwable ignored) {
        }
        ID_CACHE.put(key, id);
        return id;
    }

    /** 取宿主颜色（失败返回 0）。 */
    static int color(Context ctx, String name) {
        int id = resId(ctx, name, "color");
        if (id == 0) return 0;
        try {
            return ctx.getColor(id);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 图标 tint 颜色。
     *
     * <p>实测问题：官方的 `_ok` 图标变体是为<b>深色底</b>设计的（白色图形），
     * 我们的面板是浅色底 → 选中时图标变白即"消失"。
     * 因此必须显式 tint，而不是依赖 drawable 自身颜色。</p>
     */
    private static int tintSelected(Context ctx) {
        // 优先用主题蓝（浅色底上对比度远好于 airpods_connected_icon 的亮绿）
        int c = color(ctx, "coui_color_primary_blue");
        if (c == 0) c = color(ctx, "coui_theme_primary_color");
        if (c == 0) c = color(ctx, "airpods_connected_icon");
        if (c == 0) c = color(ctx, "icon_black");
        return (c != 0) ? c : 0xFF1F6FEB;
    }

    private static int tintNormal(Context ctx) {
        int c = color(ctx, "text_secondary");
        if (c == 0) c = color(ctx, "icon_black");
        return (c != 0) ? c : 0xFF888888;
    }

    static Drawable drawable(Context ctx, String name) {
        int id = resId(ctx, name, "drawable");
        if (id == 0) return null;
        try {
            return ctx.getDrawable(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /**
     * 降噪选中态：与 EQ 同一套**淡蓝**配色 —— 浅蓝圆底 + 主题蓝图标。
     *
     * <p>若想切回官方那种"实心蓝圆底 + 白图标"，把
     * {@link #ANC_SOLID_BLUE} 改成 {@code true} 即可。</p>
     */
    private static final boolean ANC_SOLID_BLUE = false;

    private static Drawable selectedChip(Context ctx) {
        int blue = primaryBlue(ctx);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(ANC_SOLID_BLUE
                ? blue
                : ((0x33 << 24) | (blue & 0x00FFFFFF)));   // 20% 主题蓝
        return g;
    }

    /** 未选中态的浅灰圆底（官方同款）。 */
    private static Drawable normalChip(Context ctx) {
        int c = color(ctx, "airpods_noise_button_bg");
        if (c == 0) c = 0xFFEBEBEB;
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(c);
        return g;
    }

    /**
     * 主题蓝（选中态统一使用）。
     *
     * <p><b>踩坑记录</b>：{@code coui_theme_primary_color} 的实际值是
     * <b>纯白 #FFFFFFFF</b>（不是蓝色！），一旦回退到它，"选中态"就变成全白、
     * 与卡片背景无法区分。因此这里只使用确认是蓝色的资源，并显式排除白色。</p>
     */
    static int primaryBlue(Context ctx) {
        int[] candidates = {
                color(ctx, "coui_color_primary_blue"),          // #ff0066ff
                color(ctx, "coui_color_primary_on_popup_blue"), // #ff0066ff
                color(ctx, "couiDefaultPrimaryR"),              // #ff347cff
        };
        for (int c : candidates) {
            if (c == 0) continue;
            if (android.graphics.Color.alpha(c) < 200) continue;   // 半透明不要
            // 排除近白色（避免取到 coui_theme_primary_color 那种白色）
            int r = android.graphics.Color.red(c);
            int g = android.graphics.Color.green(c);
            int b = android.graphics.Color.blue(c);
            if (r > 230 && g > 230 && b > 230) continue;
            return c;
        }
        return 0xFF0066FF;
    }

    /** 在浅底上更好辨认的深蓝（用于文字）。 */
    private static int deepBlue(Context ctx) {
        int c = primaryBlue(ctx);
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(c, hsv);
        hsv[2] = Math.max(0.35f, hsv[2] * 0.78f);   // 降低明度约 22%
        return android.graphics.Color.HSVToColor(hsv);
    }

    /**
     * EQ 选中态：**浅蓝底 + 深蓝文字**（圆角矩形）。
     *
     * <p>教训：早期直接给 EQ 标签套「纯蓝底」，但文字仍是深灰 →
     * 蓝底压深灰字，<b>几乎看不见</b>。此处改为官方常用的"浅色底 + 主题色文字"搭配，
     * 保证对比度充足。</p>
     */
    private static Drawable eqSelectedBg(Context ctx) {
        int blue = primaryBlue(ctx);
        int soft = (0x40 << 24) | (blue & 0x00FFFFFF);   // 25% 透明度主题蓝
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(soft);
        g.setCornerRadius(dp(ctx, 14));
        return g;
    }

    /**
     * 白色圆角卡片容器（官方降噪控件的承载方式）。
     *
     * <p>对比官方面板：底色是<b>实心浅灰</b>，控件放在<b>白色圆角卡片</b>上（带轻微投影）。
     * 我们之前把控件直接摊在底色上，视觉上没有层次。</p>
     */
    static LinearLayout card(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(ctx, 8), dp(ctx, 12), dp(ctx, 8), dp(ctx, 12));

        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(0xFFFFFFFF);
        g.setCornerRadius(dp(ctx, 16));
        card.setBackground(g);
        try {
            card.setElevation(dp(ctx, 1));
        } catch (Throwable ignored) {
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4));
        card.setLayoutParams(lp);
        return card;
    }

    // ------------------------------------------------------------------
    // 降噪：官方四档图标控件
    // ------------------------------------------------------------------

    /** 一档的元数据。 */
    private static final class AncItem {
        final int mode;
        final String iconBase;
        final String label;
        AncItem(int mode, String iconBase, String label) {
            this.mode = mode; this.iconBase = iconBase; this.label = label;
        }
    }

    /**
     * NiceHCK 的 ANC 四档 → 宿主图标族映射。
     *
     * <p>顺序按用户要求：<b>深度 → 降噪 → 关闭 → 通透</b>。</p>
     *
     * <p>宿主只有 4 套图标族（adaptive / transparent / noise_reduction / close），
     * 而 NiceHCK 有 4 档降噪语义，因此「深度降噪」复用 adaptive 图标族
     * （视觉上表达"更强/智能"），其余一一对应。</p>
     */
    private static final AncItem[] ANC_ITEMS = {
            new AncItem(NhckProtocol.ANC_DEEP,        "adaptive_noise",    "深度"),
            new AncItem(NhckProtocol.ANC_NORMAL,      "noise_reduction",   "降噪"),
            new AncItem(NhckProtocol.ANC_OFF,         "close_noise",       "关闭"),
            new AncItem(NhckProtocol.ANC_TRANSPARENT, "transparent_noise", "通透"),
    };

    /**
     * 官方风格降噪控件（四档）。
     *
     * <p><b>高亮机制与 EQ 完全一致</b>：选中项给格子套
     * 官方 {@code noise_button_selected_bg} 背景 + 文字加粗；未选中则清空背景。
     * 不换 drawable、不做视图增删 —— EQ 已验证这套机制有效。</p>
     */
    static View buildAncRow(final Context ctx, final NhckLink link) {
        Dumper.diag("[UI] buildAncRow 构建（cur=" + NhckProtocol.ancName(link.ancMode) + "）");
        final LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8));

        // 行背景留空：直接用面板底色，避免 noise_control_bg 的浅灰块与面板底色混色
        // （早期这里套了 noise_control_bg，视觉上出现"脏"色）

        for (final AncItem item : ANC_ITEMS) {
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), dp(ctx, 4));
            cell.setClickable(true);
            cell.setFocusable(true);

            // 图标放在「圆形底座」里 —— 官方就是圆底包住图标
            int circle = dp(ctx, 56);
            FrameLayout iconWrap = new FrameLayout(ctx);
            iconWrap.setTag("iconWrap");
            ImageView iv = new ImageView(ctx);
            Drawable d = drawable(ctx, item.iconBase + "_not");
            if (d == null) d = drawable(ctx, item.iconBase);
            if (d != null) iv.setImageDrawable(d);
            int isz = dp(ctx, 26);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(isz, isz);
            ilp.gravity = Gravity.CENTER;
            iconWrap.addView(iv, ilp);
            cell.addView(iconWrap, new LinearLayout.LayoutParams(circle, circle));

            // 点击区=整个格子，用 tag 记录 ANC 模式
            cell.setTag(item.mode);

            TextView tv = new TextView(ctx);
            tv.setText(item.label);
            tv.setTextSize(12f);
            tv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(ctx, 6);
            cell.addView(tv, tlp);

            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Dumper.log("[UI] 点击 降噪=" + NhckProtocol.ancName(item.mode));
                    try {
                        link.send(NhckProtocol.setAnc(item.mode),
                                "SET_ANC " + NhckProtocol.ancName(item.mode));
                    } catch (Throwable t) {
                        Dumper.log("[UI] send 异常: " + t);
                    }
                    link.ancMode = item.mode;
                    refreshAnc(ctx, row, item.mode);
                }
            });

            row.addView(cell, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }

        // ★ 用局部 row 引用做刷新（与 EQ 完全一致），并记录当前行供回读刷新
        sAncRowRef = row;
        refreshAnc(ctx, row, link.ancMode);

        link.addListener(new NhckLink.Listener() {
            @Override public void onState(NhckLink l) {
                final LinearLayout r = sAncRowRef;
                if (r == null) return;
                final Context c = r.getContext();
                final int mode = l.ancMode;
                r.post(new Runnable() {
                    @Override public void run() { refreshAnc(c, r, mode); }
                });
            }
        });

        return row;
    }

    /** 当前存活的降噪行（仅用于回读时定位；刷新逻辑与 EQ 相同）。 */
    private static volatile LinearLayout sAncRowRef;

    /**
     * 刷新降噪选中态 —— **逐行照搬 EQ 的 refreshEq 写法**。
     *
     * @param row 降噪行（调用方持有引用，不用静态字段遍历）
     */
    static void refreshAnc(Context ctx, LinearLayout row, int currentMode) {
        if (row == null) return;
        final int blue = deepBlue(ctx);
        final int onTint = ANC_SOLID_BLUE ? 0xFFFFFFFF : blue;   // 淡蓝底 → 蓝图标
        final int nor = tintNormal(ctx);
        final Drawable selBg = selectedChip(ctx);     // 淡蓝圆底
        final Drawable norBg = normalChip(ctx);       // 灰圆底
        StringBuilder trace = new StringBuilder("[UI] refreshAnc cur=")
                .append(NhckProtocol.ancName(currentMode)).append(" -> ");
        for (int i = 0; i < row.getChildCount(); i++) {
            View cell = row.getChildAt(i);
            Object tag = cell.getTag();
            if (!(tag instanceof Integer)) continue;
            int mode = (Integer) tag;
            boolean on = (mode == currentMode);

            // ① 底：选中=蓝圆底 + 白图标；未选中=灰圆底 + 深灰图标（与官方一致）
            Object wrapTag = (cell instanceof ViewGroup && ((ViewGroup) cell).getChildCount() > 0)
                    ? ((ViewGroup) cell).getChildAt(0).getTag() : null;
            View iconWrap = null;
            if ("iconWrap".equals(wrapTag)) {
                iconWrap = ((ViewGroup) cell).getChildAt(0);
            }
            if (iconWrap != null) {
                iconWrap.setBackground(on ? selBg : norBg);
            } else {
                cell.setBackground(on ? selBg : null);
            }

            // ② 图标：选中=主题蓝（配淡蓝圆底），未选中=深灰
            if (iconWrap instanceof ViewGroup
                    && ((ViewGroup) iconWrap).getChildCount() > 0) {
                View icon = ((ViewGroup) iconWrap).getChildAt(0);
                if (icon instanceof ImageView) {
                    ((ImageView) icon).setImageTintList(
                            android.content.res.ColorStateList.valueOf(on ? onTint : nor));
                    icon.setAlpha(1f);
                }
            } else if (iconWrap instanceof ImageView) {
                ((ImageView) iconWrap).setImageTintList(
                        android.content.res.ColorStateList.valueOf(on ? onTint : nor));
            }

            // ③ 文字：选中=主题蓝加粗（与 EQ 完全同一套配色）
            if (cell instanceof ViewGroup && ((ViewGroup) cell).getChildCount() > 1) {
                View txt = ((ViewGroup) cell).getChildAt(1);
                if (txt instanceof TextView) {
                    TextView t = (TextView) txt;
                    t.setTextColor(on ? blue : nor);
                    t.setAlpha(1f);
                    t.setTypeface(null, on
                            ? android.graphics.Typeface.BOLD
                            : android.graphics.Typeface.NORMAL);
                }
            }
            trace.append(NhckProtocol.ancName(mode)).append('=').append(on ? "ON" : "off").append(' ');
        }
        Dumper.diag(trace.toString());
    }

    // ------------------------------------------------------------------
    // EQ：横向标签，选中用官方选中底色
    // ------------------------------------------------------------------

    /** EQ 顺序（按用户要求删除「游戏优化」，避免与下方「游戏模式」开关冲突）。 */
    private static final int[] EQ_ORDER = {
            NhckProtocol.EQ_BALANCED, NhckProtocol.EQ_BASS, NhckProtocol.EQ_PURE,
            NhckProtocol.EQ_FINE, NhckProtocol.EQ_VOCAL,
    };

    /**
     * EQ 显示名（单行完整名，按用户要求的格式）。
     */
    private static String eqDisplay(int eq) {
        String full = NhckProtocol.eqName(eq);
        if (full == null) return "";
        int p = full.indexOf('(');
        if (p > 0) full = full.substring(0, p);
        return full.trim();
    }

    /**
     * EQ 选择区 —— **两行两列**按钮网格（按用户要求的排版）。
     *
     * <p>行1：均衡中正 / 欧美澎湃；行2：真律还原 / 细腻佳音（再一行：温婉人声）。
     * 每个按钮是圆角胶囊，选中态与降噪共用同一套淡蓝配色。</p>
     */
    static View buildEqRow(final Context ctx, final NhckLink link) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 8));

        wrap.addView(sectionTitle(ctx, "均衡器"));

        final LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);

        final Drawable selBg = eqSelectedBg(ctx);
        final int PER_ROW = 2;   // 每行两个

        LinearLayout row = null;
        for (int i = 0; i < EQ_ORDER.length; i++) {
            final int eq = EQ_ORDER[i];
            if (i % PER_ROW == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = dp(ctx, 8);
                column.addView(row, rlp);
            }

            TextView tv = new TextView(ctx);
            tv.setText(eqDisplay(eq));
            tv.setTextSize(13f);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(ctx, 6), dp(ctx, 10), dp(ctx, 6), dp(ctx, 10));
            tv.setClickable(true);
            tv.setTag(eq);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Dumper.log("[UI] 点击 EQ=" + NhckProtocol.eqName(eq));
                    try {
                        link.send(NhckProtocol.setEq(eq), "SET_EQ " + NhckProtocol.eqName(eq));
                    } catch (Throwable t) {
                        Dumper.log("[UI] send 异常: " + t);
                    }
                    link.eqMode = eq;
                    refreshEq(ctx, column, link.eqMode, selBg);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0);
            row.addView(tv, lp);
        }

        refreshEq(ctx, column, link.eqMode, selBg);

        link.addListener(new NhckLink.Listener() {
            @Override public void onState(final NhckLink l) {
                column.post(new Runnable() {
                    @Override public void run() {
                        refreshEq(ctx, column, l.eqMode, selBg);
                    }
                });
            }
        });

        wrap.addView(column);
        return wrap;
    }

    /** 刷新 EQ 选中态（column 为两行容器的外层）。 */
    private static void refreshEq(Context ctx, LinearLayout column, int current, Drawable selBg) {
        final int blue = deepBlue(ctx);
        final int nor = tintNormal(ctx);
        for (int r = 0; r < column.getChildCount(); r++) {
            View rowV = column.getChildAt(r);
            if (!(rowV instanceof ViewGroup)) continue;
            ViewGroup rowG = (ViewGroup) rowV;
            for (int i = 0; i < rowG.getChildCount(); i++) {
                View v = rowG.getChildAt(i);
                Object tag = v.getTag();
                if (!(tag instanceof Integer)) continue;
                boolean selected = ((Integer) tag) == current;
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    tv.setTextColor(selected ? blue : nor);
                    tv.setAlpha(selected ? 1f : 0.8f);
                    tv.setTypeface(null, selected
                            ? android.graphics.Typeface.BOLD
                            : android.graphics.Typeface.NORMAL);
                }
                v.setBackground(selected ? selBg : null);
            }
        }
    }

    // ------------------------------------------------------------------
    // 功能开关（协议已全部实测）
    // ------------------------------------------------------------------

    static View buildFeatureSwitches(final Context ctx, final NhckLink link) {
        Dumper.diag("[UI] buildFeatureSwitches 开始");
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 8));

        wrap.addView(sectionTitle(ctx, "功能"));

        final String[][] defs = {
                {"游戏模式"}, {"入耳检测"}, {"双设备连接"}, {"抗风噪"},
        };
        for (int i = 0; i < defs.length; i++) {
            try {
                wrap.addView(buildOneSwitch(ctx, i, link));
                Dumper.diag("[UI]   开关[" + defs[i][0] + "] OK");
            } catch (Throwable t) {
                Dumper.log("[UI]   开关[" + defs[i][0] + "] 失败: " + t);
            }
        }
        Dumper.diag("[UI] buildFeatureSwitches 完成 kids=" + wrap.getChildCount());
        return wrap;
    }

    /** 按索引构建单个功能开关。 */
    private static View buildOneSwitch(final Context ctx, int idx, final NhckLink link) {
        switch (idx) {
            case 0:
                return mkSwitch(ctx, "游戏模式",
                        new StateGetter() { @Override public boolean get(NhckLink l) { return l.gameMode; } },
                        new StateSetter() { @Override public void set(NhckLink l, boolean on) {
                            l.send(NhckProtocol.setGameMode(on), "SET_GAME " + on); l.gameMode = on; } },
                        link);
            case 1:
                return mkSwitch(ctx, "入耳检测",
                        new StateGetter() { @Override public boolean get(NhckLink l) { return l.inEar; } },
                        new StateSetter() { @Override public void set(NhckLink l, boolean on) {
                            l.send(NhckProtocol.setInEar(on), "SET_INEAR " + on); l.inEar = on; } },
                        link);
            case 2:
                return mkSwitch(ctx, "双设备连接",
                        new StateGetter() { @Override public boolean get(NhckLink l) { return l.dualConn; } },
                        new StateSetter() { @Override public void set(NhckLink l, boolean on) {
                            l.send(NhckProtocol.setDualConn(on), "SET_DUAL " + on); l.dualConn = on; } },
                        link);
            default:
                return mkSwitch(ctx, "抗风噪",
                        new StateGetter() { @Override public boolean get(NhckLink l) { return l.wind; } },
                        new StateSetter() { @Override public void set(NhckLink l, boolean on) {
                            l.send(NhckProtocol.setWind(on), "SET_WIND " + on); l.wind = on; } },
                        link);
        }
    }

    private interface StateGetter { boolean get(NhckLink l); }
    private interface StateSetter { void set(NhckLink l, boolean on); }

    private static View mkSwitch(final Context ctx, String label,
                                 final StateGetter getter, final StateSetter setter,
                                 final NhckLink link) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 紧凑：上下各 2dp，避免 4 个开关占满一屏
        row.setPadding(dp(ctx, 4), dp(ctx, 2), dp(ctx, 4), dp(ctx, 2));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(14f);
        row.addView(tv, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(ctx);
        sw.setChecked(getter.get(link));
        // 缩小 Switch 自身尺寸，进一步压缩高度
        sw.setMinimumHeight(dp(ctx, 28));
        sw.setPadding(0, 0, 0, 0);

        // ★ 开关配色（对齐用户提供的参考图）：
        //   参考图中【两种状态的滑块都是蓝色】—— 识别度由蓝色滑块提供，
        //   轨道只作淡淡的底色区分。
        //   OFF：轨道 = 降噪「未选中」的浅灰 #EBEBEB
        //   ON ：轨道 = EQ「选中」的浅蓝（主题蓝 25%）
        //   踩坑：曾把 OFF 滑块设为白色 → 白滑块 + 浅灰轨道在白卡片上几乎不可见。
        //   注意：宿主主题 colorAccent 是白色（coui_theme_primary_color=#ffffffff），
        //   不显式指定 tint 会出现"白滑块 + 白轨道"完全看不见的情况。
        try {
            int blue = primaryBlue(ctx);
            int soft = (0x40 << 24) | (blue & 0x00FFFFFF);   // 25% 主题蓝（与 EQ 选中一致）
            // OFF 轨道：必须用「足够深的灰」才看得见。
            // 实测教训：先用 #EBEBEB（降噪未选中圆底色）→ 在白卡片上几乎消融，
            // 因为那个色是作为"实心圆"存在的，而轨道是很细的一条，需要更高对比度。
            int offGrey = 0xFFC8C8C8;

            // 滑块：两种状态都用主题蓝（与参考图一致）
            sw.setThumbTintList(android.content.res.ColorStateList.valueOf(blue));
            // 轨道：OFF 浅灰 / ON 浅蓝
            sw.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][]{
                            new int[]{android.R.attr.state_checked},
                            new int[]{}
                    },
                    new int[]{soft, offGrey}));
            Dumper.diag("[UI] 开关配色: thumb=#" + String.format("%08X", blue)
                    + "（两态同色） track(ON)=#" + String.format("%08X", soft)
                    + " track(OFF)=#" + String.format("%08X", offGrey));
        } catch (Throwable t) {
            Dumper.log("[UI] 开关 tint 设置失败（已忽略）: " + t);
        }
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                Dumper.log("[UI] 切换 " + b.getText() + " -> " + checked);
                try {
                    setter.set(NhckLink.get(), checked);
                } catch (Throwable t) {
                    Dumper.log("[UI] setter 异常: " + t);
                }
            }
        });
        row.addView(sw);

        link.addListener(new NhckLink.Listener() {
            @Override public void onState(final NhckLink l) {
                row.post(new Runnable() {
                    @Override public void run() {
                        boolean want = getter.get(l);
                        if (sw.isChecked() != want) sw.setChecked(want);
                    }
                });
            }
        });

        return row;
    }

    private static TextView sectionTitle(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setPadding(dp(ctx, 4), dp(ctx, 4), 0, dp(ctx, 4));
        tv.setAlpha(0.7f);
        return tv;
    }
}
