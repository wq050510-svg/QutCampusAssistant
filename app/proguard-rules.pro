# ============================================================================
# QutCampusAssistant R8 / ProGuard 规则
# 目标：开启混淆与资源压缩后仍保持 Gson 解析、Room、Compose、Glance 正常，
#       同时尽量裁剪无用代码与日志调用，缩小安装包体积。
# ============================================================================

# ---------- 基础属性：崩溃还原与注解 ----------
# 保留行号，Release 崩溃栈才有意义（配合 -renamesourcefileattribute 隐藏真实文件名）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# ---------- Kotlin ----------
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
# when 语句枚举映射表（Kotlin 编译器生成，删除会导致 when 分支异常）
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# ---------- Gson：数据模型不可混淆 ----------
# 工程使用 gson.fromJson(json, XxxModel::class.java) 直接反序列化，字段名即 JSON key
-keep class cn.edu.qut.campus.data.model.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# 泛型签名与 TypeToken（Gson 反射读取）
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.Expose <fields>;
}

# ---------- OkHttp / Okio（含可选依赖缺失告警）----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn kotlinx.parcelize.**

# ---------- 裁剪调试日志：v/d/i 全部内联移除（保留 w/e 便于线上排错）----------
# 收益：减少方法数、避免敏感信息（学号/请求体）出现在 logcat
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# ---------- Room ----------
# Room 自带 consumer 规则，无需整体 keep（整体 keep 会让 R8 无法裁剪 Room 体积）
-dontwarn androidx.room.paging.**

# ---------- 系统按类名实例化的组件 ----------
# 小组件 Provider 由系统通过清单里的类名反射创建，显式保底（AGP 也会为清单组件生成 keep）
-keep class * extends android.appwidget.AppWidgetProvider { *; }
-keep class cn.edu.qut.campus.QutApplication { *; }
-keep class cn.edu.qut.campus.ui.MainActivity { *; }
