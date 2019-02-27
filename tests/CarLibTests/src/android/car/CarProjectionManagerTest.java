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

package android.car;

import static android.car.CarProjectionManager.ProjectionAccessPointCallback.ERROR_GENERIC;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.car.CarProjectionManager.ProjectionAccessPointCallback;
import android.car.testapi.CarProjectionController;
import android.car.testapi.FakeCar;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class CarProjectionManagerTest {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Captor
    private ArgumentCaptor<Intent> mIntentArgumentCaptor;

    @Spy
    private final Context mContext = ApplicationProvider.getApplicationContext();

    private static final int DEFAULT_TIMEOUT_MS = 1000;

    private CarProjectionManager mProjectionManager;
    private CarProjectionController mController;
    private ApCallback mApCallback;

    @Before
    public void setUp() {
        FakeCar fakeCar = FakeCar.createFakeCar(mContext);
        mController = fakeCar.getCarProjectionController();
        mProjectionManager =
                (CarProjectionManager) fakeCar.getCar().getCarManager(Car.PROJECTION_SERVICE);
        assertThat(mProjectionManager).isNotNull();

        mApCallback = new ApCallback();
    }

    @Test
    public void startAp_fail() throws InterruptedException {
        mController.setWifiConfiguration(null);

        mProjectionManager.startProjectionAccessPoint(mApCallback);
        mApCallback.mFailed.await(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(mApCallback.mFailureReason).isEqualTo(ERROR_GENERIC);
    }

    @Test
    public void startAp_success() throws InterruptedException {
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.SSID = "Hello";
        wifiConfiguration.BSSID = "AA:BB:CC:CC:DD:EE";
        wifiConfiguration.preSharedKey = "password";

        mController.setWifiConfiguration(wifiConfiguration);

        mProjectionManager.startProjectionAccessPoint(mApCallback);
        mApCallback.mStarted.await(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat(mApCallback.mWifiConfiguration).isEqualTo(wifiConfiguration);
    }

    @Test
    public void registerProjectionRunner() throws CarNotConnectedException {
        Intent intent = new Intent("my_action");
        intent.setPackage("my.package");
        mProjectionManager.registerProjectionRunner(intent);

        verify(mContext).bindService(mIntentArgumentCaptor.capture(), any(),
                eq(Context.BIND_AUTO_CREATE));
        assertThat(mIntentArgumentCaptor.getValue()).isEqualTo(intent);
    }

    private static class ApCallback extends ProjectionAccessPointCallback {
        CountDownLatch mStarted = new CountDownLatch(1);
        CountDownLatch mFailed = new CountDownLatch(1);
        int mFailureReason = -1;
        WifiConfiguration mWifiConfiguration;

        @Override
        public void onStarted(WifiConfiguration wifiConfiguration) {
            mWifiConfiguration = wifiConfiguration;
            mStarted.countDown();
        }

        @Override
        public void onStopped() {
        }

        @Override
        public void onFailed(int reason) {
            mFailureReason = reason;
            mFailed.countDown();
        }
    }
}
