package com.sensors.app;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

/**
 * OP13T 传感器面板 —— 单 Activity + WebView。
 * 原生侧只做两件事：
 *   1) 枚举全部传感器并吐出规格 JSON（getSensors）
 *   2) 批量注册监听，把最新值以 ~5Hz 节流推给页面（window.__push）
 *
 * 注意：d8 8.2.2 工具链限制——所有嵌套类必须 static、不得用匿名类，
 * 集合一律 raw type（见 skill apk-build-aarch64 坑 #31）。
 */
public class MainActivity extends Activity {

    private WebView web;
    private Bridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        bridge = new Bridge(this, web);
        web.addJavascriptInterface(bridge, "Sensors");
        setContentView((View) web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onPause() {
        bridge.stopAll();          // 后台不耗电：全部注销
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        // 页面按自己的 UI 状态决定重新监听哪些
        web.evaluateJavascript("window.__resume && window.__resume()", null);
    }

    // ------------------------------------------------------------------ //

    public static class Bridge implements SensorEventListener {

        final Activity act;
        final WebView web;
        final SensorManager sm;
        final java.util.List all;      // List<Sensor>
        final boolean[] active;
        final float[][] vals;
        final long[] ts;               // 事件时间戳(ns)
        final int[] acc;               // 精度
        final boolean[] dirty;
        final Handler main = new Handler(Looper.getMainLooper());
        final Pusher pusher;
        boolean pushRunning = false;

        Bridge(Activity act, WebView web) {
            this.act = act;
            this.web = web;
            this.sm = (SensorManager) act.getSystemService(Context.SENSOR_SERVICE);
            this.all = sm.getSensorList(Sensor.TYPE_ALL);
            int n = all.size();
            active = new boolean[n];
            vals = new float[n][];
            ts = new long[n];
            acc = new int[n];
            dirty = new boolean[n];
            pusher = new Pusher(this);
        }

        /** 全量传感器规格，一次拉走 */
        @JavascriptInterface
        public String getSensors() {
            JSONArray a = new JSONArray();
            for (int i = 0; i < all.size(); i++) {
                Sensor s = (Sensor) all.get(i);
                JSONObject o = new JSONObject();
                try {
                    o.put("i", i);
                    o.put("name", s.getName());
                    o.put("vendor", s.getVendor());
                    o.put("ver", s.getVersion());
                    o.put("type", s.getType());
                    o.put("stype", s.getStringType());
                    o.put("pw", s.getPower());          // mA
                    o.put("res", s.getResolution());
                    o.put("rng", s.getMaximumRange());
                    o.put("mind", s.getMinDelay());      // µs，负值=单次/触发型
                    o.put("maxd", s.getMaxDelay());
                    o.put("fifo", s.getFifoMaxEventCount());
                    o.put("rm", s.getReportingMode());  // 0连续 1变化 2单次 3特殊触发
                    o.put("wk", s.isWakeUpSensor());
                    a.put(o);
                } catch (Exception ignored) { }
            }
            return a.toString();
        }

        /** 返回 "ok" 或失败原因（JS 据此区分权限缺失 vs 硬失败） */
        @JavascriptInterface
        public String listen(int idx) {
            if (idx < 0 || idx >= all.size()) return "索引越界";
            Sensor s = (Sensor) all.get(idx);
            try {
                boolean ok = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
                if (ok) {
                    active[idx] = true;
                    startPush();
                    return "ok";
                }
                return "注册被拒绝";
            } catch (Exception e) {
                String m = e.getMessage();
                return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
            }
        }

        @JavascriptInterface
        public void stop(int idx) {
            if (idx < 0 || idx >= all.size()) return;
            sm.unregisterListener(this, (Sensor) all.get(idx));
            active[idx] = false;
        }

        @JavascriptInterface
        public void stopAll() {
            sm.unregisterListener(this);
            for (int i = 0; i < active.length; i++) active[i] = false;
        }


        // ---- SensorEventListener（主线程回调） ---- //

        public void onSensorChanged(SensorEvent e) {
            int idx = indexOf(e.sensor);
            if (idx < 0) return;
            vals[idx] = Arrays.copyOf(e.values, e.values.length); // 系统复用数组，必须拷贝
            ts[idx] = e.timestamp;
            acc[idx] = e.accuracy;
            dirty[idx] = true;
            // 单次触发型传感器触发后自动失效，重新挂上以等待下一次
            if (e.sensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
                sm.unregisterListener(this, e.sensor);
                sm.registerListener(this, e.sensor, SensorManager.SENSOR_DELAY_UI);
            }
        }

        public void onAccuracyChanged(Sensor s, int accuracy) { }

        private int indexOf(Sensor s) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).equals(s)) return i;
            }
            return -1;
        }

        // ---- 节流推送：200ms 一批，只推有新数据的传感器 ---- //

        void startPush() {
            if (!pushRunning) {
                pushRunning = true;
                main.postDelayed(pusher, 200);
            }
        }

        void flush() {
            JSONObject o = new JSONObject();
            boolean any = false;
            for (int i = 0; i < all.size(); i++) {
                if (!dirty[i] || vals[i] == null) { dirty[i] = false; continue; }
                JSONObject d = new JSONObject();
                try {
                    JSONArray v = new JSONArray();
                    for (int k = 0; k < vals[i].length; k++) v.put((double) vals[i][k]);
                    d.put("t", ts[i]);
                    d.put("a", acc[i]);
                    d.put("v", v);
                    o.put(String.valueOf(i), d);
                    any = true;
                } catch (Exception ignored) { }
                dirty[i] = false;
            }
            if (!any) return;
            web.evaluateJavascript(
                    "window.__push && window.__push(" + o.toString() + ")", null);
        }
    }

    /** 周期推送器（不能匿名类，单独 static） */
    public static class Pusher implements Runnable {
        final Bridge b;
        Pusher(Bridge b) { this.b = b; }
        public void run() {
            try { b.flush(); } catch (Exception ignored) { }
            boolean any = false;
            for (int i = 0; i < b.active.length; i++) {
                if (b.active[i]) { any = true; break; }
            }
            if (any) b.main.postDelayed(this, 200);
            else b.pushRunning = false;
        }
    }

}
