# Tamper / signature verification — must not be renamed or removed
-keep class me.fss.orbal.utils.SignatureVerifier { *; }

# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Room entities and DAOs
-keep class me.fss.orbal.data.local.entities.** { *; }
-keep class me.fss.orbal.data.local.dao.** { *; }

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# Keep SmolLM native bridge
-keep class me.fss.orbal.smollm.** { *; }

# Keep serialization
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# Keep data classes used for JSON export/import
-keep class me.fss.orbal.data.repository.ExportData { *; }
-keep class me.fss.orbal.data.repository.ExportedChat { *; }
-keep class me.fss.orbal.data.repository.ExportedMessage { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# Kotlin
-dontwarn kotlin.**
-keepattributes SourceFile,LineNumberTable

# Navigation
-keep class androidx.navigation.** { *; }

# Compose — keep lambdas and composable function metadata
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}

# Markdown renderer
-keep class com.mikepenz.markdown.** { *; }
-dontwarn com.mikepenz.markdown.**

# Tink crypto (used by EncryptedSharedPreferences)
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.**
-dontwarn javax.annotation.**
-dontwarn com.google.auto.value.**
-dontwarn org.joda.time.**
