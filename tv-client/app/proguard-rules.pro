# TV Diagnostics Proguard Rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.tvdiagnostics.app.data.models.** { *; }
