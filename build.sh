#!/bin/sh
# 构建脚本（需要 JDK 17+、Android SDK 的 android.jar、d8、aapt、zipalign、apksigner）
# 依赖的 SDK 路径可以通过环境变量覆盖
set -e

SDK="${SDK:-/workspace/sdk}"
AJ="$SDK/android-36/android.jar"
AJ_RES="$SDK/android-29/android.jar"
D8="$SDK/android-16/lib/d8.jar"
API="${API_JAR:-$SDK/libxposed-api-102.jar}"
KS="${KS:-keystore.jks}"
KS_PASS="${KS_PASS:-android}"
KS_ALIAS="${KS_ALIAS:-eta}"

OUT="build"
APK_NAME="BackTapX.apk"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "== javac"
javac -source 17 -target 17 -nowarn -Xlint:-options \
    -classpath "$AJ:$API" -d "$OUT/classes" $(find src -name '*.java')

echo "== d8"
java -cp "$D8" com.android.tools.r8.D8 --min-api 26 --lib "$AJ" \
    --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "== aapt"
aapt package -f -M AndroidManifest.xml -S res -I "$AJ_RES" -F "$OUT/base.apk" \
    --min-sdk-version 34 --target-sdk-version 36

echo "== package"
cd "$OUT"
cp dex/classes.dex .
mkdir -p META-INF/xposed
cp ../xposed/module.prop ../xposed/java_init.list ../xposed/scope.list META-INF/xposed/
zip -q base.apk classes.dex \
    META-INF/xposed/module.prop META-INF/xposed/java_init.list META-INF/xposed/scope.list

zipalign -f 4 base.apk aligned.apk

echo "== sign"
if [ ! -f "../$KS" ]; then
    echo "未找到 $KS，生成一个自签名密钥（仅用于本地测试）"
    keytool -genkeypair -keystore "../$KS" -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=BackTapX, O=BackTapX, C=CN"
fi

java -jar "$SDK/android-16/lib/apksigner.jar" sign \
    --ks "../$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --out "$APK_NAME" aligned.apk

echo "== done: $OUT/$APK_NAME"
