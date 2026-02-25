# RocksmithToTab ProGuard / R8 rules
# Applied only to release builds (minifyEnabled = true).

# ── Keep application entry points ────────────────────────────────────────────
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# ── AndroidX WorkManager workers (referenced by class name at runtime) ────────
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }

# ── Kotlin data classes used in JSON parsing ──────────────────────────────────
# Manifest2014 and Attributes2014 are parsed reflectively via org.json field names.
-keep class com.rocksmithtab.data.psarc.Manifest2014 { *; }
-keep class com.rocksmithtab.data.psarc.Attributes2014 { *; }
-keep class com.rocksmithtab.data.psarc.TuningStrings { *; }

# ── Crypto (javax.crypto is platform-provided; keep call sites) ───────────────
-dontwarn javax.crypto.**

# ── XML DOM (platform-provided) ───────────────────────────────────────────────
-dontwarn org.w3c.dom.**
-dontwarn javax.xml.**

# ── Kotlin intrinsics ────────────────────────────────────────────────────────
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }
-keep class kotlin.Metadata { *; }

# ── Standard Android optimisations ───────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable   # preserves stack traces in crash reports
-renamesourcefileattribute SourceFile

# ── Suppress warnings for missing optional classes ───────────────────────────
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
