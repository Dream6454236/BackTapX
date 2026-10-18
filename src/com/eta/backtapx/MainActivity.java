package com.eta.backtapx;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private EditText cmdInput;
    private TextView status;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE);
        int pad = 56;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, 140, pad, 60);

        TextView title = new TextView(this);
        title.setTextSize(24);
        title.setText("背部双击");
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setTextSize(15);
        hint.setPadding(0, 24, 0, 24);
        hint.setText("亮屏时在手机背面连敲三下（三下总时长 0.8 秒内），执行下面的命令。息屏自动停止采样。\n"
                + "命令以 root 执行，首次会弹一次授权。");
        root.addView(hint);

        cmdInput = new EditText(this);
        cmdInput.setTextSize(15);
        cmdInput.setHint("例如：log -t BackTap hit");
        cmdInput.setText(prefs.getString(BridgeService.KEY_CMD, ""));
        root.addView(cmdInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, 32, 0, 32);
        root.addView(status);

        root.addView(button("保存命令", v -> save()));
        root.addView(button("测试执行一次", v -> test()));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        refresh();
    }

    private Button button(String text, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = 16;
        button.setLayoutParams(params);
        return button;
    }

    private void save() {
        prefs.edit().putString(BridgeService.KEY_CMD, cmdInput.getText().toString().trim()).apply();
        refresh();
    }

    private void refresh() {
        String cmd = prefs.getString(BridgeService.KEY_CMD, "");
        status.setText("当前命令：" + (cmd.isEmpty() ? "（空，双击不执行任何操作）" : cmd)
                + "\n\n生效条件：LSPosed 里启用本模块并勾选\"系统界面\"，然后重启系统界面。");
    }

    private void test() {
        final String cmd = cmdInput.getText().toString().trim();
        if (cmd.isEmpty()) {
            status.setText("状态：命令为空，未执行");
            return;
        }
        prefs.edit().putString(BridgeService.KEY_CMD, cmd).apply();
        status.setText("状态：执行中…");
        new Thread(() -> {
            String result;
            try {
                String su = new java.io.File("/system/bin/su").canExecute() ? "/system/bin/su" : "su";
                Process process = new ProcessBuilder(su, "-c", cmd)
                        .redirectErrorStream(true).start();
                List<String> lines = new ArrayList<>();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null && lines.size() < 10) {
                    lines.add(line);
                }
                boolean done = process.waitFor(15, TimeUnit.SECONDS);
                if (!done) process.destroy();
                result = "exit=" + (done ? process.exitValue() : -1)
                        + (lines.isEmpty() ? "" : " · " + String.join(" ", lines));
            } catch (Throwable throwable) {
                Log.w("EtaBackTapX", "test failed", throwable);
                result = "失败: " + throwable;
            }
            final String text = result;
            runOnUiThread(() -> status.setText("状态：测试 " + text));
        }, "backtapx-test").start();
    }
}
