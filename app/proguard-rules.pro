# Native code (MojoLauncher/Pojav libs, LWJGL, JRE bridge) looks Java classes up by name through JNI,
# so every app-owned package stays whole. Only third-party libraries (Compose, AndroidX, Kotlin) shrink.
-keep class org.lwjgl.** { *; }
-keep class net.kdt.** { *; }
-keep class git.artdeell.** { *; }
-keep class com.oracle.** { *; }
-keep class ru.cw.launcher.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature,Exceptions
-dontwarn org.lwjgl.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
