# BackTapX · 背部双击

ColorOS / Android 上的「背部敲击」Xposed 模块：**亮屏状态下在手机背面连敲三下，执行一条自定义的 shell 命令**。

系统本身没有这个能力（ColorOS 里那套 `key_knock_shell_twice` 依赖专用传感器，多数机型没有），本模块在 SystemUI 进程里自己注册加速度计做判定，命中后经桥接服务以 root 执行命令。

## 特性

- 只在亮屏时采样，息屏自动注销监听，不后台耗电
- 三击判定，带间隔一致性与脉冲尖锐度检查，抑制摇晃、拿起、放下等误触
- 动作完全自定义：填什么命令就执行什么（以 root 运行）
- 无 UI 常驻，无通知，配置界面只有一页

## 环境要求

| 项 | 要求 |
|---|---|
| Android | 14+（targetSdk 36） |
| Root | KernelSU / Magisk 均可 |
| 框架 | LSPosed 2.x（API 102） |
| 作用域 | `com.android.systemui` |

## 安装

1. 安装 `BackTapX.apk`
2. LSPosed → 模块 → 启用「背部双击」，作用域勾选「系统界面」
3. 重启系统界面（`kill $(pidof com.android.systemui)`）或重启手机
4. 打开「背部双击」填入命令，点保存
5. 首次触发会请求一次 root 授权

## 判定逻辑

采样 200Hz 加速度计，计算合模长 `mag`，取相邻样本差 `|Δmag|`：

1. `|Δmag| < 3.0 m/s²` → 忽略
2. 连续超阈值帧数 > 3 → 判定为持续运动（摇晃），静默 250ms
3. `|dz| < 0.5 × max(|dx|, |dy|)` → 判定为横向摆动，忽略
4. 脉冲时间戳记录：`PEAK_GAP_MS` 内视为同一击的回波
5. 连续三次敲击需满足：
   - 相邻间隔 120–450ms
   - 三击总时长 800ms 内
   - 两次间隔之差 ≤ 200ms
   - 三次的 z 方向符号一致
6. 命中后冷却 1500ms

参数都在 `src/com/eta/backtapx/TapModule.java` 顶部，可按机型手感调整。

## 为什么息屏不能用

`bmi26x` 这类加速度计在多数机型上是 **non-wakeup** 传感器，息屏后事件只会堆在 FIFO 里，不会唤醒 AP。要息屏即时响应必须维持 wakelock，功耗不可接受；真正的低功耗方案需要传感器子系统（SSC/AON）侧的常驻检测器，那是厂商固件的事。

## 构建

```sh
SDK=/path/to/android-sdk ./build.sh
```

需要 JDK 17+、`android.jar`、`d8`、`aapt`、`zipalign`、`apksigner`、`libxposed-api-102.jar`。

产物：`build/BackTapX.apk`。

图标由 `make_icon.py` 生成（需要 Python 3 + Pillow）：

```sh
python3 make_icon.py
```

## 目录结构

```
AndroidManifest.xml     应用与桥接服务声明
src/…/TapModule.java    Xposed 入口，SystemUI 内采样与判定
src/…/BridgeService.java 只接受系统界面调用的桥接服务，负责执行命令
src/…/MainActivity.java 命令配置界面
xposed/                 module.prop / java_init.list / scope.list
res/mipmap-*/           图标
make_icon.py            图标生成脚本
build.sh                构建脚本
```

## 安全说明

- 命令以 **root** 身份执行，请只填入自己确认安全的内容
- `BridgeService` 只接受 `uid 1000`、`uid 2000` 或 `com.android.systemui` 的调用
- 仓库不包含任何签名密钥，`keystore.jks` 已被 `.gitignore` 排除

## 已知限制

- 息屏状态不触发（见上）
- 手机平放在桌面时敲击幅度较小，可能需要稍用力
- 不同手机壳厚度、握持姿势会影响灵敏度，参数可能需要自行微调

## 版本

- v1.5 — 改三击判定，加入脉冲尖锐度检查与 z 轴方向约束，阈值降到 3.0
- v1.4 — 加入 z 轴主导约束（对轻敲过于严格，已放宽）
- v1.3 — 双击改为三击，收紧间隔
- v1.2 — 加入应用图标
- v1.1 — 修正 su 路径解析
- v1.0 — 初版：双敲执行命令
