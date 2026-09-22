# ProGuard rules for QutCampusAssistant
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class cn.edu.qut.campus.data.model.** { *; }
