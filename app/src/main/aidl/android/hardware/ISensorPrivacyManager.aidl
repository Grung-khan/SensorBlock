/*
 * Mirror of AOSP android13-release
 *   frameworks/base/core/java/android/hardware/ISensorPrivacyManager.aidl
 *
 * WHY A MIRROR AND NOT A RAW TRANSACTION INDEX
 * --------------------------------------------
 * Binder transaction codes are assigned by declaration order. By mirroring the
 * AOSP interface *in the same order*, our generated Stub.Proxy emits exactly the
 * codes the Android 13 SensorPrivacyService expects:
 *
 *     ... 6 = isSensorPrivacyEnabled()
 *         7 = isCombinedToggleSensorPrivacyEnabled(int)
 *         8 = isToggleSensorPrivacyEnabled(int, int)
 *         9 = setSensorPrivacy(boolean)          <-- the all-sensor kill switch
 *        10 = setToggleSensorPrivacy(int,int,int,boolean)
 *
 * Newer releases only *append* methods (see §3.5 of AGENTS.md), so the codes
 * above are stable on this device. We still never hard-code a number: the
 * generated Proxy supplies it.
 *
 * The interface is reached through
 *   ISensorPrivacyManager.Stub.asInterface(
 *       new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("sensor_privacy")))
 * which makes the call arrive at the service with uid 2000 (shell) — the only
 * non-root identity holding MANAGE_SENSOR_PRIVACY.
 */
package android.hardware;

import android.hardware.ISensorPrivacyListener;

/** @hide */
interface ISensorPrivacyManager {
    // ============ beginning of transactions also used from native code ============
    boolean supportsSensorToggle(int toggleType, int sensor);

    void addSensorPrivacyListener(in ISensorPrivacyListener listener);

    void addToggleSensorPrivacyListener(in ISensorPrivacyListener listener);

    void removeSensorPrivacyListener(in ISensorPrivacyListener listener);

    void removeToggleSensorPrivacyListener(in ISensorPrivacyListener listener);

    boolean isSensorPrivacyEnabled();

    boolean isCombinedToggleSensorPrivacyEnabled(int sensor);

    boolean isToggleSensorPrivacyEnabled(int toggleType, int sensor);

    void setSensorPrivacy(boolean enable);

    void setToggleSensorPrivacy(int userId, int source, int sensor, boolean enable);

    void setToggleSensorPrivacyForProfileGroup(int userId, int source, int sensor, boolean enable);
    // ============= end of transactions also used from native code ================

    void suppressToggleSensorPrivacyReminders(int userId, int sensor, IBinder token,
            boolean suppress);

    boolean requiresAuthentication();

    void showSensorUseDialog(int sensor);
}
