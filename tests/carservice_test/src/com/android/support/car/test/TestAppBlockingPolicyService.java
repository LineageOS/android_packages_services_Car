package com.android.support.car.test;

import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.support.car.content.pm.AppBlockingPackageInfo;
import android.support.car.content.pm.CarAppBlockingPolicy;
import android.support.car.content.pm.CarAppBlockingPolicyService;
import android.util.Log;

public class TestAppBlockingPolicyService extends CarAppBlockingPolicyService {
    private static final String TAG = TestAppBlockingPolicyService.class.getSimpleName();

    private static TestAppBlockingPolicyService sInstance;
    private static boolean sSetPolicy = true;

    public static synchronized TestAppBlockingPolicyService getInstance() {
        return sInstance;
    }

    public static synchronized void controlPolicySettingFromService(boolean setPolicy) {
        Log.i(TAG, "controlPolicySettingFromService:" + setPolicy);
        sSetPolicy = setPolicy;
    }

    @Override
    protected CarAppBlockingPolicy getAppBlockingPolicy() {
        synchronized (TestAppBlockingPolicyService.class) {
            sInstance = this;
            if (sSetPolicy == false) {
                Log.i(TAG, "getAppBlockingPolicy returning null");
                return null;
            }
        }
        PackageManager pm = getPackageManager();
        String packageName = getPackageName();
        Signature[] signatures;
        try {
            signatures = pm.getPackageInfo(packageName,
                    PackageManager.GET_SIGNATURES).signatures;
        } catch (NameNotFoundException e) {
            return null;
        }
        AppBlockingPackageInfo selfInfo = new AppBlockingPackageInfo(packageName, 0, 0, 0,
                signatures, null);
        AppBlockingPackageInfo[] whitelists = new AppBlockingPackageInfo[] { selfInfo };
        CarAppBlockingPolicy policy = new CarAppBlockingPolicy(whitelists, null);
        Log.i(TAG, "getAppBlockingPolicy, passing policy:" + policy);
        return policy;
    }
}
