# ---------------------------------------------------------------------------
# Shizuku
# ---------------------------------------------------------------------------
# The API talks to the Shizuku server through these classes reflectively.
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ---------------------------------------------------------------------------
# SensorBlock's Shizuku UserService
# ---------------------------------------------------------------------------
# Instantiated by the Shizuku server via reflection, inside a uid-2000 process.
# Both constructors and the AIDL Stub must survive shrinking.
-keep class com.sensorblock.app.backend.ShellUserService { <init>(...); *; }
-keep class com.sensorblock.app.shell.** { *; }

# ---------------------------------------------------------------------------
# Mirrored hidden AIDL
# ---------------------------------------------------------------------------
# android.hardware.ISensorPrivacyManager is normally resolved from the boot
# class loader; keep our mirror intact so the generated Stub.Proxy keeps the
# correct transaction order either way.
-keep class android.hardware.ISensorPrivacyManager { *; }
-keep class android.hardware.ISensorPrivacyManager$* { *; }
-keep class android.hardware.ISensorPrivacyListener { *; }
-keep class android.hardware.ISensorPrivacyListener$* { *; }

# ---------------------------------------------------------------------------
# Device-admin receiver is instantiated by the system.
# ---------------------------------------------------------------------------
-keep class com.sensorblock.app.admin.SensorBlockDeviceAdminReceiver { *; }

# Kotlin metadata / coroutines are handled by the default rules.
