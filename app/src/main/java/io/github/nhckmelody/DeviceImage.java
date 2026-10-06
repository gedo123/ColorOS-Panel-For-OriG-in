package io.github.nhckmelody;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.InputStream;

/**
 * 用原道官方产品图替换官方面板里的「通用耳机图」。
 *
 * <p>素材来源：从官方 App {@code com.yuandao.nicehck} 的
 * {@code assets/images/origin/preview.png} 提取（720×720，原道 OriG in 产品图），
 * 已打包进本模块的 {@code assets/nhck_origin.png}。</p>
 *
 * <p>读取方式：{@code createPackageContext(模块包名)} 拿到模块自己的
 * {@code AssetManager} —— 宿主进程内可用，无需反射或 root。</p>
 */
final class DeviceImage {

    /** 模块包名（用于 createPackageContext 读自己的 assets）。 */
    private static final String MODULE_PKG = "io.github.nhckmelody";

    /** 打包进模块的产品图。 */
    private static final String ASSET = "nhck_origin.png";

    /** 官方面板里展示耳机图的 ImageView 资源名。 */
    private static final String TARGET_ID = "bt_heaset_imageView";

    private static volatile Drawable sDrawable;
    private static volatile boolean sLoaded;

    private DeviceImage() {}

    /**
     * 替换官方面板中的耳机图片。
     *
     * @param host 宿主 Context（用于解析宿主资源 id）
     * @param root 面板根视图（从中查找目标 ImageView）
     */
    static void replaceHeadsetImage(Context host, View root) {
        if (host == null || root == null) return;
        try {
            int id = host.getResources().getIdentifier(TARGET_ID, "id",
                    "com.heytap.mydevices");
            if (id == 0) {
                Dumper.log("[IMG] 未找到 " + TARGET_ID + " 资源 id，跳过");
                return;
            }
            View v = root.findViewById(id);
            if (!(v instanceof ImageView)) {
                // 弹窗视图树里可能存在多个 root，逐层兜底查找
                v = findByType(root, id);
            }
            if (!(v instanceof ImageView)) {
                Dumper.log("[IMG] " + TARGET_ID + " 不是 ImageView（" + v + "），跳过");
                return;
            }

            Drawable d = load(host);
            if (d == null) {
                Dumper.log("[IMG] 产品图加载失败，保留官方原图");
                return;
            }
            ImageView iv = (ImageView) v;
            iv.setImageDrawable(d);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            // 清除可能存在的着色（官方通用图可能带 tint）
            try {
                iv.setImageTintList(null);
            } catch (Throwable ignored) {
            }
            Dumper.log("[IMG] ✓ 已替换为原道官方产品图 ("
                    + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight() + ")");
        } catch (Throwable t) {
            Dumper.log("[IMG] 替换失败（已忽略）: " + t);
        }
    }

    /** 深度查找指定 id 的 ImageView。 */
    private static View findByType(View v, int id) {
        if (v.getId() == id) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByType(g.getChildAt(i), id);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 从模块 assets 读取产品图（只解码一次并缓存）。 */
    private static Drawable load(Context host) {
        if (sLoaded) return sDrawable;
        synchronized (DeviceImage.class) {
            if (sLoaded) return sDrawable;
            InputStream in = null;
            try {
                Context moduleCtx = host.createPackageContext(MODULE_PKG, 0);
                in = moduleCtx.getAssets().open(ASSET);
                Bitmap bmp = BitmapFactory.decodeStream(in);
                if (bmp == null) {
                    Dumper.log("[IMG] decodeStream 返回 null");
                    sLoaded = true;
                    return null;
                }
                sDrawable = new BitmapDrawable(host.getResources(), bmp);
                Dumper.log("[IMG] 已加载模块产品图 " + bmp.getWidth() + "x" + bmp.getHeight());
            } catch (Throwable t) {
                Dumper.log("[IMG] 读取 assets 失败: " + t);
            } finally {
                try {
                    if (in != null) in.close();
                } catch (Throwable ignored) {
                }
            }
            sLoaded = true;
            return sDrawable;
        }
    }
}
