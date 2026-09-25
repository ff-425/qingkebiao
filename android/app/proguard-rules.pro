# 只做优化和裁剪，不混淆类名。
# 崩溃日志要导出来给人看，混淆过的堆栈（a.b.c）根本没法对上代码；
# 名字留着对包体积影响很小，性能上 R8 的优化照样全部生效。
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# kotlinx.serialization：序列化器是编译期生成的，但 R8 会把 @Serializable 类的
# 伴生 serializer() 当成没人用而删掉。库自带的规则覆盖不到我们自己的数据类。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.qingkebiao.timetable.** {
    *** Companion;
}
-keepclasseswithmembers class com.qingkebiao.timetable.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.qingkebiao.timetable.**$$serializer { *; }

# Glance / RemoteViews 靠反射找 Receiver 和布局
-keep class com.qingkebiao.timetable.widget.** { *; }

# WebView 里注入的 JS 回调
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
