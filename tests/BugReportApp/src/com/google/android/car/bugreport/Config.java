/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.car.bugreport;

import android.app.ActivityThread;
import android.os.Build;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;

/**
 * Contains config for BugReport App.
 *
 * <p>The config is kept synchronized with {@link DeviceConfig#NAMESPACE_CAR}.
 *
 * <ul>To get/set the flags via adb:
 *   <li>{@code adb shell device_config get car bugreport_upload_destination}
 *   <li>{@code adb shell device_config put car bugreport_upload_destination gcs}
 *   <li>{@code adb shell device_config delete car bugreport_upload_destination}
 * </ul>
 */
final class Config {
    private static final String TAG = Config.class.getSimpleName();

    /**
     * A string flag, can be one of {@code null} or {@link #UPLOAD_DESTINATION_GCS}.
     */
    private static final String KEY_BUGREPORT_UPLOAD_DESTINATION = "bugreport_upload_destination";

    /**
     * A value for {@link #KEY_BUGREPORT_UPLOAD_DESTINATION}.
     *
     * Upload bugreports to GCS. Only works in {@code userdebug} or {@code eng} builds.
     */
    private static final String UPLOAD_DESTINATION_GCS = "gcs";

    /**
     * A system property to force enable uploading new bugreports to GCS.
     * Unlike {@link #UPLOAD_DESTINATION_GCS}, it bypasses the {@code userdebug} build check.
     */
    private static final String PROP_FORCE_ENABLE_GCS_UPLOAD =
            "android.car.bugreport.force_enable_gcs_upload";

    /**
     * Temporary flag to retain the old behavior.
     *
     * Default is {@code true}.
     *
     * TODO(b/143183993): Disable auto-upload to GCS after testing DeviceConfig.
     */
    private static final String ENABLE_AUTO_UPLOAD = "android.car.bugreport.enableautoupload";

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private String mUploadDestination = null;

    void start() {
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_CAR,
                ActivityThread.currentApplication().getMainExecutor(), this::onPropertiesChanged);
        updateConstants();
    }

    private void onPropertiesChanged(DeviceConfig.Properties properties) {
        if (properties.getKeyset().contains(KEY_BUGREPORT_UPLOAD_DESTINATION)) {
            updateConstants();
        }
    }

    /** If new bugreports should be scheduled for uploading. */
    private boolean getAutoUpload() {
        if (isTempForceAutoUploadGcsEnabled()) {
            Log.d(TAG, "Enabling auto-upload because ENABLE_AUTO_UPLOAD is true");
            return true;
        }
        // TODO(b/144851443): Enable auto-upload only if upload destination is Gcs until
        //                    we create a way to allow implementing OEMs custom upload logic.
        return isUploadDestinationGcs();
    }

    /**
     * Returns {@link true} if bugreport upload destination is GCS.
     */
    boolean isUploadDestinationGcs() {
        if (isTempForceAutoUploadGcsEnabled()) {
            Log.d(TAG, "Setting upload dest to GCS ENABLE_AUTO_UPLOAD is true");
            return true;
        }
        // NOTE: enable it only for userdebug builds, unless it's force enabled using a system
        //       property.
        return (UPLOAD_DESTINATION_GCS.equals(getUploadDestination()) && Build.IS_DEBUGGABLE)
                || SystemProperties.getBoolean(PROP_FORCE_ENABLE_GCS_UPLOAD, /* def= */ false);
    }

    /** Returns {@code true} if the bugreport should be auto-uploaded to a cloud. */
    boolean autoUploadBugReport(MetaBugReport bugReport) {
        return getAutoUpload() && bugReport.getType() == MetaBugReport.TYPE_INTERACTIVE;
    }

    private static boolean isTempForceAutoUploadGcsEnabled() {
        return SystemProperties.getBoolean(ENABLE_AUTO_UPLOAD, /* def= */ true);
    }

    /**
     * Returns value of a flag {@link #KEY_BUGREPORT_UPLOAD_DESTINATION}.
     */
    private String getUploadDestination() {
        synchronized (mLock) {
            return mUploadDestination;
        }
    }

    private void updateConstants() {
        synchronized (mLock) {
            mUploadDestination = DeviceConfig.getString(DeviceConfig.NAMESPACE_CAR,
                    KEY_BUGREPORT_UPLOAD_DESTINATION, /* defaultValue= */ null);
        }
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "car.bugreport.Config:");

        pw.print(prefix + "  ");
        pw.print("getAutoUpload");
        pw.print("=");
        pw.println(getAutoUpload() ? "true" : "false");

        pw.print(prefix + "  ");
        pw.print("getUploadDestination");
        pw.print("=");
        pw.println(getUploadDestination());

        pw.print(prefix + "  ");
        pw.print("isUploadDestinationGcs");
        pw.print("=");
        pw.println(isUploadDestinationGcs());
    }
}
