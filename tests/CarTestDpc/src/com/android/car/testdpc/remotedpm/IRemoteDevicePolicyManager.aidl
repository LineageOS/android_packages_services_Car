package com.android.car.testdpc.remotedpm;

import android.content.ComponentName;

interface IRemoteDevicePolicyManager {
    /*
     * Reboots the device.
     */
    void reboot(in ComponentName admin);
}
