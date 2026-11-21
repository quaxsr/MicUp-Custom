# MicUp-Custom（当前未开启 minify，规则作为预留）
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# —— native 音频核心（第2阶段）——
# 开启 minify 时防止 native <methods> 被重命名，否则 JNI 符号查找失败
-keepclasseswithmembernames class com.micup.custom.audio.NativeAudioEngine { native <methods>; }
# native 侧经 JNI 按名回调 Listener 方法（onNativeFrame/onNativeError），一并防混淆
-keep class com.micup.custom.audio.NativeAudioEngine$Listener { *; }
