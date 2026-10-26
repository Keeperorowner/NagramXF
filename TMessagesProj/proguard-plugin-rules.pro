# plugin flavor only
-keep class com.chaquo.python.** { *; }
-keep class * implements com.chaquo.python.StaticProxy { *; }
-keep class android_utils.** { *; }
-keep class client_utils.** { *; }
-keep class com.exteragram.messenger.plugins.** { *; }
-keep class de.robv.android.xposed.** { *; }

# Hook filters evaluate MVEL expressions; the compiler emits bytecode that refers to these classes
# by name at runtime, so they must survive shrinking and renaming.
-keep class org.mvel2.** { *; }
# MVEL's optional JSR-223 bridge has no implementation on Android.
-dontwarn javax.script.**
-dontwarn org.mvel2.jsr223.**
