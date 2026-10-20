# Proguard rules for fasterTunnel
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.fastertunnel.app.core.api.** { *; }
