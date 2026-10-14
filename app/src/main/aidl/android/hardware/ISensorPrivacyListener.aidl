/*
 * Mirror of AOSP android13-release
 *   frameworks/base/core/java/android/hardware/ISensorPrivacyListener.aidl
 *
 * Only the wire shape matters: SensorBlock registers a listener to observe
 * changes made outside the app (Quick Settings tile, Settings, other tools).
 */
package android.hardware;

oneway interface ISensorPrivacyListener {
    void onSensorPrivacyChanged(int toggleType, int sensor, boolean enabled);
}
