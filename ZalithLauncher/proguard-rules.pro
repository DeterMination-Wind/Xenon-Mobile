-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn com.github.luben.zstd.**
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.**

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}

# Launcher
-keep class org.lwjgl.glfw.CallbackBridge {
    *;
}
-keep class com.oracle.dalvik.VMLauncher {
    *;
}

#
## Hilt
#-keep class dagger.hilt.** { *; }
#-keep class javax.inject.** { *; }
#-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
#-keepclasseswithmembers class * {
#    @dagger.hilt.* <methods>;
#}

# Prevent R8 from over-optimizing constructors (causes StackOverflow with Hilt + proguard-android-optimize.txt)
-keepclassmembers,allowobfuscation class * {
    @dagger.hilt.internal.GeneratedEntryPoint <init>(...);
}
-keep,allowobfuscation @dagger.hilt.android.AndroidEntryPoint class *


-keep class com.movtery.zalithlauncher.bridge.** { *; }
-keep class com.movtery.zalithlauncher.utils.device.VulkanChecker {
    *;
}
-keep class com.movtery.zalithlauncher.utils.device.VulkanCapabilities {
    *;
}
-keep interface com.movtery.zalithlauncher.utils.device.VulkanLogCallback {
    *;
}
-keep class com.movtery.zalithlauncher.game.input.CriticalNativeTest {
    *;
}

# Libraries
-keep class com.github.steveice10.opennbt.** { *; }

# Gson reflects over the Mindustry catalog: it maps JSON keys to the *field names* (the catalog
# model has no @SerializedName) and instantiates the classes through their no-argument
# constructors. Without these rules a release build fails on device with
# "Abstract classes can't be instantiated! ... Class name: mq5".
-keepattributes Signature
-keep class com.movtery.zalithlauncher.game.mindustry.MindustryCatalogManifest { *; }
-keep class com.movtery.zalithlauncher.game.mindustry.MindustryArtifact { *; }
-keep class com.movtery.zalithlauncher.game.mindustry.CatalogMirror { *; }
-keep enum com.movtery.zalithlauncher.game.mindustry.MindustryVariant { *; }
-keep enum com.movtery.zalithlauncher.game.mindustry.MindustryBackend { *; }
