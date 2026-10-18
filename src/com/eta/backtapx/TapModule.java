package com.eta.backtapx;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 亮屏状态下双击机身背面，经 BridgeService 用 su 执行模块里配置的 shell 命令。
 * 只在亮屏时注册加速度计，息屏立即注销，避免常驻耗电。
 */
public final class TapModule extends XposedModule {

    private static final String TAG = "EtaBackTapX";
    private static final String SYSTEM_UI = "com.android.systemui";

    private static final float DIFF_THRESHOLD = 3.0f;
    private static final long PEAK_GAP_MS = 35;
    private static final long TAP_MIN_GAP_MS = 120;
    private static final long TAP_MAX_GAP_MS = 450;
    private static final long TRIPLE_WINDOW_MS = 800;
    private static final long GAP_TOLERANCE_MS = 200;
    private static final long COOLDOWN_MS = 1500;

    private static volatile TapModule instance;

    private static volatile boolean started;
    private static Context hostContext;
    private static SensorManager sensorManager;
    private static Sensor accelerometer;
    private static Handler sensorHandler;
    private static HandlerThread sensorThread;
    private static volatile boolean sampling;

    private static float prevMag = Float.NaN;
    private static float prevX;
    private static float prevY;
    private static float prevZ;
    private static int tapSign;
    private static int overCount;
    private static long motionUntil;
    private static long lastPeakMs;
    private static long lastFireMs;
    private static int tapCount;
    private static long firstTapMs;
    private static long secondTapMs;

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!SYSTEM_UI.equals(param.getPackageName())) {
            return;
        }
        try {
            install(param.getClassLoader());
        } catch (Throwable throwable) {
            plog(Log.ERROR, TAG, "install_failed: " + throwable, throwable);
        }
    }

    private void install(ClassLoader classLoader) {
        instance = this;
        if (hookApplication(classLoader)) return;
        if (hookService(classLoader)) return;
        plog(Log.ERROR, TAG, "no hook point found", null);
    }

    private static void plog(int priority, String tag, String message, Throwable throwable) {
        Log.println(priority, tag, message);
        TapModule module = instance;
        if (module == null) return;
        try {
            module.log(priority, tag, message, throwable);
        } catch (Throwable ignored) {
            // LSPosed 日志通道不可用时忽略
        }
    }

    private boolean hookApplication(ClassLoader classLoader) {
        try {
            Class<?> type = Class.forName(SYSTEM_UI + ".SystemUIApplication", false, classLoader);
            Method onCreate = type.getDeclaredMethod("onCreate");
            hook(onCreate).setId("backtapx:sysui-app").intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Context) {
                        start((Context) self);
                    }
                } catch (Throwable throwable) {
                    plog(Log.ERROR, TAG, "start_failed: " + throwable, throwable);
                }
                return result;
            });
            plog(Log.INFO, TAG, "hooked SystemUIApplication.onCreate", null);
            return true;
        } catch (Throwable throwable) {
            plog(Log.WARN, TAG, "SystemUIApplication hook unavailable: " + throwable, null);
            return false;
        }
    }

    private boolean hookService(ClassLoader classLoader) {
        try {
            Class<?> type = Class.forName(SYSTEM_UI + ".SystemUIService", false, classLoader);
            Method onCreate = type.getDeclaredMethod("onCreate");
            hook(onCreate).setId("backtapx:sysui-service").intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Context) {
                        start((Context) self);
                    }
                } catch (Throwable throwable) {
                    plog(Log.ERROR, TAG, "start_failed: " + throwable, throwable);
                }
                return result;
            });
            plog(Log.INFO, TAG, "hooked SystemUIService.onCreate", null);
            return true;
        } catch (Throwable throwable) {
            plog(Log.WARN, TAG, "SystemUIService hook unavailable: " + throwable, null);
            return false;
        }
    }

    private static synchronized void start(Context context) {
        if (started) return;
        Context app = context.getApplicationContext();
        hostContext = app != null ? app : context;
        sensorManager = (SensorManager) hostContext.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) {
            plog(Log.ERROR, TAG, "no sensor service", null);
            return;
        }
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null) {
            plog(Log.ERROR, TAG, "no accelerometer", null);
            return;
        }
        sensorThread = new HandlerThread("eta-backtap-sensor");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                    startSampling();
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    stopSampling();
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                hostContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                hostContext.registerReceiver(receiver, filter);
            }
        } catch (Throwable throwable) {
            plog(Log.ERROR, TAG, "registerReceiver_failed: " + throwable, throwable);
        }

        started = true;
        PowerManager powerManager =
                (PowerManager) hostContext.getSystemService(Context.POWER_SERVICE);
        if (powerManager != null && powerManager.isInteractive()) {
            startSampling();
        }
        plog(Log.INFO, TAG, "ready", null);
    }

    private static synchronized void startSampling() {
        if (sampling || sensorManager == null || accelerometer == null || sensorHandler == null) {
            return;
        }
        prevMag = Float.NaN;
        tapCount = 0;
        overCount = 0;
        motionUntil = 0L;
        sampling = true;
        sensorManager.registerListener(listener, accelerometer, 5000, sensorHandler);
        plog(Log.INFO, TAG, "sampling_start", null);
    }

    private static synchronized void stopSampling() {
        if (!sampling || sensorManager == null) return;
        sampling = false;
        sensorManager.unregisterListener(listener);
        prevMag = Float.NaN;
        tapCount = 0;
        overCount = 0;
        motionUntil = 0L;
        plog(Log.INFO, TAG, "sampling_stop", null);
    }

    private static final SensorEventListener listener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            try {
                float x = event.values[0];
                float y = event.values[1];
                float z = event.values[2];
                float mag = (float) Math.sqrt(x * x + y * y + z * z);
                if (Float.isNaN(prevMag)) {
                    prevX = x;
                    prevY = y;
                    prevZ = z;
                    prevMag = mag;
                    return;
                }
                float dx = x - prevX;
                float dy = y - prevY;
                float dz = z - prevZ;
                prevX = x;
                prevY = y;
                prevZ = z;
                float diff = Math.abs(mag - prevMag);
                prevMag = mag;
                long now = SystemClock.elapsedRealtime();

                if (diff < DIFF_THRESHOLD) {
                    overCount = 0;
                    return;
                }
                if (overCount < 99) overCount++;
                if (overCount > 3) {
                    // 连续多帧都在变，是持续运动（摇晃），不是敲击
                    motionUntil = now + 250L;
                    return;
                }
                if (now < motionUntil) return;

                // 敲击冲击主要沿垂直背面的 z 轴，但不要求绝对压制 x/y
                float adz = Math.abs(dz);
                float adxy = Math.max(Math.abs(dx), Math.abs(dy));
                if (adz < adxy * 0.5f) return;
                int sign = dz > 0 ? 1 : -1;

                if (now - lastPeakMs < PEAK_GAP_MS) return;
                lastPeakMs = now;

                if (tapCount > 0 && now - firstTapMs > TRIPLE_WINDOW_MS) {
                    tapCount = 0;
                }
                if (tapCount == 0) {
                    if (now - lastFireMs < COOLDOWN_MS) return;
                    tapCount = 1;
                    firstTapMs = now;
                    tapSign = sign;
                    return;
                }
                if (sign != tapSign) {
                    tapCount = 1;
                    firstTapMs = now;
                    tapSign = sign;
                    return;
                }

                long since = now - (tapCount == 1 ? firstTapMs : secondTapMs);
                if (since < TAP_MIN_GAP_MS || since > TAP_MAX_GAP_MS) {
                    tapCount = 1;
                    firstTapMs = now;
                    return;
                }
                if (tapCount == 1) {
                    tapCount = 2;
                    secondTapMs = now;
                    return;
                }

                long gap1 = secondTapMs - firstTapMs;
                long gap2 = now - secondTapMs;
                if (Math.abs(gap2 - gap1) > GAP_TOLERANCE_MS) {
                    tapCount = 1;
                    firstTapMs = now;
                    return;
                }
                tapCount = 0;
                lastFireMs = now;
                fire();
            } catch (Throwable throwable) {
                plog(Log.ERROR, TAG, "sensor_failed: " + throwable, throwable);
            }
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    private static void fire() {
        plog(Log.INFO, TAG, "double_tap", null);
        final Context context = hostContext;
        if (context == null) return;
        try {
            Intent intent = new Intent("com.eta.backtapx.action.BRIDGE")
                    .setClassName("com.eta.backtapx", "com.eta.backtapx.BridgeService");
            context.bindService(intent, new android.content.ServiceConnection() {
                @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder service) {
                    try {
                        android.os.Parcel data = android.os.Parcel.obtain();
                        android.os.Parcel reply = android.os.Parcel.obtain();
                        try {
                            service.transact(2, data, reply, 0);
                            reply.readException();
                            Log.i(TAG, "bridge_result: " + reply.readString());
                        } finally {
                            reply.recycle();
                            data.recycle();
                        }
                    } catch (Throwable throwable) {
                        plog(Log.WARN, TAG, "bridge_failed: " + throwable, null);
                    } finally {
                        try {
                            context.unbindService(this);
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                }

                @Override public void onServiceDisconnected(android.content.ComponentName name) { }
            }, Context.BIND_AUTO_CREATE);
        } catch (Throwable throwable) {
            plog(Log.WARN, TAG, "bind_failed: " + throwable, null);
        }
    }
}
