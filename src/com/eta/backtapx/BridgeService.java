package com.eta.backtapx;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 只接受系统界面（uid 1000）调用的桥接服务：读配置、用 su 执行命令。
 * 命令为空时直接返回，不做任何事。
 */
public final class BridgeService extends Service {

    static final String TAG = "EtaBackTapX";
    static final String PREFS = "cfg";
    static final String KEY_CMD = "cmd";

    static final int TRANSACTION_GET_CMD = 1;
    static final int TRANSACTION_RUN = 2;

    private static final String[] SU_CANDIDATES = {
            "/system/bin/su",
            "/system/xbin/su",
            "/debug_ramdisk/su",
            "/sbin/su",
            "/su/bin/su",
            "su",
    };
    private static final String PATH =
            "/system/bin:/system/xbin:/system/sbin:/sbin:/product/bin:/vendor/bin:/debug_ramdisk";

    private final IBinder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (!isTrustedCaller()) {
                Log.w(TAG, "untrusted caller uid=" + Binder.getCallingUid());
                return false;
            }
            if (code == TRANSACTION_GET_CMD) {
                if (reply != null) reply.writeString(currentCommand());
                return true;
            }
            if (code == TRANSACTION_RUN) {
                String result = runRoot(currentCommand());
                Log.i(TAG, "run result: " + result);
                if (reply != null) reply.writeString(result);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private String currentCommand() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String cmd = prefs.getString(KEY_CMD, "");
        return cmd == null ? "" : cmd.trim();
    }

    private boolean isTrustedCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.SYSTEM_UID || uid == Process.SHELL_UID) {
            return true;
        }
        try {
            String[] packages = getPackageManager().getPackagesForUid(uid);
            if (packages != null) {
                for (String name : packages) {
                    if ("com.android.systemui".equals(name)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // deny on failure
        }
        return false;
    }

    private static String runRoot(String command) {
        if (command == null || command.isEmpty()) {
            return "empty";
        }
        Throwable lastError = null;
        for (String su : SU_CANDIDATES) {
            if (!"su".equals(su) && !new File(su).canExecute()) {
                continue;
            }
            java.lang.Process process = null;
            try {
                ProcessBuilder builder = new ProcessBuilder(su, "-c", command);
                builder.environment().put("PATH", PATH);
                builder.redirectErrorStream(true);
                process = builder.start();
                List<String> lines = new ArrayList<>();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null && lines.size() < 20) {
                    lines.add(line);
                }
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroy();
                    return "timeout";
                }
                int code = process.exitValue();
                String output = String.join("\n", lines).trim();
                return code + (output.isEmpty() ? "" : " " + output);
            } catch (Throwable throwable) {
                lastError = throwable;
            } finally {
                if (process != null) process.destroy();
            }
        }
        return "su_unavailable " + lastError;
    }
}
