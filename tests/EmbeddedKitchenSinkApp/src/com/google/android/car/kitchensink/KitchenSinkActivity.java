/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.google.android.car.kitchensink;

import android.car.hardware.camera.CarCameraManager;
import android.car.hardware.hvac.CarHvacManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.support.car.Car;
import android.support.car.CarAppFocusManager;
import android.support.car.CarNotConnectedException;
import android.support.car.CarNotSupportedException;
import android.support.car.ServiceConnectionListener;
import android.support.car.app.menu.CarDrawerActivity;
import android.support.car.app.menu.CarMenu;
import android.support.car.app.menu.CarMenuCallbacks;
import android.support.car.app.menu.RootMenu;
import android.support.car.hardware.CarSensorEvent;
import android.support.car.hardware.CarSensorManager;
import android.support.v4.app.Fragment;
import android.util.Log;

import com.google.android.car.kitchensink.audio.AudioTestFragment;
import com.google.android.car.kitchensink.camera.CameraTestFragment;
import com.google.android.car.kitchensink.cluster.InstrumentClusterFragment;
import com.google.android.car.kitchensink.cube.CubesTestFragment;
import com.google.android.car.kitchensink.hvac.HvacTestFragment;
import com.google.android.car.kitchensink.input.InputTestFragment;
import com.google.android.car.kitchensink.job.JobSchedulerFragment;
import com.google.android.car.kitchensink.keyboard.KeyboardFragment;
import com.google.android.car.kitchensink.orientation.OrientationTestFragment;
import com.google.android.car.kitchensink.radio.RadioTestFragment;
import com.google.android.car.kitchensink.sensor.SensorsTestFragment;
import com.google.android.car.kitchensink.setting.CarServiceSettingsActivity;
import com.google.android.car.kitchensink.touch.TouchTestFragment;
import com.google.android.car.kitchensink.volume.VolumeTestFragment;

import java.util.ArrayList;
import java.util.List;

public class KitchenSinkActivity extends CarDrawerActivity {
    private static final String TAG = "KitchenSinkActivity";

    private static final String MENU_AUDIO = "audio";
    private static final String MENU_CAMERA = "camera";
    private static final String MENU_HVAC = "hvac";
    private static final String MENU_QUIT = "quit";
    private static final String MENU_JOB = "job_scheduler";
    private static final String MENU_KEYBOARD = "keyboard";
    private static final String MENU_CLUSTER = "inst cluster";
    private static final String MENU_INPUT_TEST = "input test";
    private static final String MENU_RADIO = "radio";
    private static final String MENU_SENSORS = "sensors";
    private static final String MENU_VOLUME_TEST = "volume test";
    private static final String MENU_TOUCH_TEST = "touch test";
    private static final String MENU_CUBES_TEST = "cubes test";
    private static final String MENU_CAR_SETTINGS = "car service settings";
    private static final String MENU_ORIENTATION = "orientation test";

    private Car mCarApi;
    private CarCameraManager mCameraManager;
    private CarHvacManager mHvacManager;
    private CarSensorManager mCarSensorManager;
    private CarAppFocusManager mCarAppFocusManager;

    private AudioTestFragment mAudioTestFragment;
    private RadioTestFragment mRadioTestFragment;
    private SensorsTestFragment mSensorsTestFragment;
    private CameraTestFragment mCameraTestFragment;
    private HvacTestFragment mHvacTestFragment;
    private JobSchedulerFragment mJobFragment;
    private KeyboardFragment mKeyboardFragment;
    private InstrumentClusterFragment mInstrumentClusterFragment;
    private InputTestFragment mInputTestFragment;
    private VolumeTestFragment mVolumeTestFragment;
    private TouchTestFragment mTouchTestFragment;
    private CubesTestFragment mCubesTestFragment;
    private OrientationTestFragment mOrientationFragment;

    private final CarSensorManager.CarSensorEventListener mListener =
            new CarSensorManager.CarSensorEventListener() {
        @Override
        public void onSensorChanged(CarSensorEvent event) {
            switch (event.sensorType) {
                case CarSensorManager.SENSOR_TYPE_DRIVING_STATUS:
                    Log.d(TAG, "driving status:" + event.intValues[0]);
                    break;
            }
        }
    };

    public KitchenSinkActivity(Proxy proxy, Context context, Car car) {
        super(proxy, context, car);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        resetTitle();
        setScrimColor(Color.LTGRAY);
        setLightMode();
        setCarMenuCallbacks(new MyCarMenuCallbacks());
        setContentView(R.layout.kitchen_sink_activity);

        // Connection to Car Service does not work for non-automotive yet.
        if (getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            mCarApi = Car.createCar(getContext(), mServiceConnectionListener);
            mCarApi.connect();
        }
        Log.i(TAG, "onCreate");
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "onStart");
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Log.i(TAG, "onRestart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCarSensorManager != null) {
            try {
                mCarSensorManager.unregisterListener(mListener);
            } catch (CarNotConnectedException e) {
                Log.e(TAG, "Failed to unregister car seonsor listener", e);
            }
        }
        if (mCarApi != null) {
            mCarApi.disconnect();
        }
        Log.i(TAG, "onDestroy");
    }

    private void resetTitle() {
        setTitle(getContext().getString(R.string.app_title));
    }

    private void showFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.contents, fragment)
                .commit();
    }

    private final ServiceConnectionListener mServiceConnectionListener =
            new ServiceConnectionListener() {
        @Override
        public void onServiceConnected(ComponentName name) {
            Log.d(TAG, "Connected to Car Service");
            try {
                mCameraManager = (CarCameraManager) mCarApi.getCarManager(android.car.Car
                        .CAMERA_SERVICE);
                mHvacManager = (CarHvacManager) mCarApi.getCarManager(android.car.Car.HVAC_SERVICE);
                mCarSensorManager = (CarSensorManager) mCarApi.getCarManager(Car.SENSOR_SERVICE);
                mCarSensorManager.registerListener(mListener,
                        CarSensorManager.SENSOR_TYPE_DRIVING_STATUS,
                        CarSensorManager.SENSOR_RATE_NORMAL);
                mCarAppFocusManager =
                        (CarAppFocusManager) mCarApi.getCarManager(Car.APP_FOCUS_SERVICE);
            } catch (CarNotConnectedException e) {
                Log.e(TAG, "Car is not connected!", e);
            } catch (CarNotSupportedException e) {
                Log.e(TAG, "Car is not supported!", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Disconnect from Car Service");
        }

        @Override
        public void onServiceSuspended(int cause) {
            Log.d(TAG, "Car Service connection suspended");
        }

        @Override
        public void onServiceConnectionFailed(int cause) {
            Log.d(TAG, "Car Service connection failed");
        }
    };

    private final class MyCarMenuCallbacks extends CarMenuCallbacks {
        /** Id for the root menu */
        private static final String ROOT = "ROOT";

        @Override
        public RootMenu onGetRoot(Bundle hints) {
            return new RootMenu(ROOT);
        }

        @Override
        public void onLoadChildren(String parentId, CarMenu result) {
            List<CarMenu.Item> items = new ArrayList<>();
            if (parentId.equals(ROOT)) {
                String[] allMenus = {
                        MENU_AUDIO, MENU_RADIO, MENU_CAMERA, MENU_HVAC, MENU_JOB, MENU_KEYBOARD,
                        MENU_CLUSTER, MENU_INPUT_TEST, MENU_SENSORS, MENU_VOLUME_TEST,
                        MENU_TOUCH_TEST, MENU_CUBES_TEST, MENU_CAR_SETTINGS, MENU_ORIENTATION,
                        MENU_QUIT
                };
                for (String menu : allMenus) {
                    items.add(new CarMenu.Builder(menu).setText(menu).build());
                }
            }
            result.sendResult(items);
        }

        @Override
        public void onItemClicked(String id) {
            Log.d(TAG, "onItemClicked id=" + id);
            if (id.equals(MENU_AUDIO)) {
                if (mAudioTestFragment == null) {
                    mAudioTestFragment = new AudioTestFragment();
                }
                showFragment(mAudioTestFragment);
            } else if (id.equals(MENU_RADIO)) {
                if (mRadioTestFragment == null) {
                    mRadioTestFragment = new RadioTestFragment();
                }
                showFragment(mRadioTestFragment);
            } else if (id.equals(MENU_SENSORS)) {
                if (mSensorsTestFragment == null) {
                    mSensorsTestFragment = new SensorsTestFragment();
                }
                showFragment(mSensorsTestFragment);
            } else if (id.equals(MENU_CAMERA)) {
                if (mCameraManager != null) {
                    if (mCameraTestFragment == null) {
                        mCameraTestFragment = new CameraTestFragment();
                        mCameraTestFragment.setCameraManager(mCameraManager);
                    }
                    // Don't allow camera fragment to start if we don't have a manager.
                    showFragment(mCameraTestFragment);
                }
            } else if (id.equals(MENU_HVAC)) {
                if (mHvacManager != null) {
                    if (mHvacTestFragment == null) {
                        mHvacTestFragment = new HvacTestFragment();
                        mHvacTestFragment.setHvacManager(mHvacManager);
                    }
                    // Don't allow HVAC fragment to start if we don't have a manager.
                    showFragment(mHvacTestFragment);
                }
            } else if (id.equals(MENU_JOB)) {
                if (mJobFragment == null) {
                    mJobFragment = new JobSchedulerFragment();
                }
                showFragment(mJobFragment);
            } else if (id.equals(MENU_KEYBOARD)) {
                if (mKeyboardFragment == null) {
                    mKeyboardFragment = new KeyboardFragment();
                }
                showFragment(mKeyboardFragment);
            } else if (id.equals(MENU_CLUSTER)) {
                if (mInstrumentClusterFragment == null) {
                    mInstrumentClusterFragment = new InstrumentClusterFragment();
                }
                showFragment(mInstrumentClusterFragment);
            } else if (id.equals(MENU_INPUT_TEST)) {
                if (mInputTestFragment == null) {
                    mInputTestFragment = new InputTestFragment();
                }
                showFragment(mInputTestFragment);
            } else if (id.equals(MENU_VOLUME_TEST)) {
                if (mVolumeTestFragment == null) {
                    mVolumeTestFragment = new VolumeTestFragment();
                }
                showFragment(mVolumeTestFragment);
            } else if (id.equals(MENU_TOUCH_TEST)) {
                if (mTouchTestFragment == null) {
                    mTouchTestFragment = new TouchTestFragment();
                }
                showFragment(mTouchTestFragment);
            } else if (id.equals(MENU_CUBES_TEST)) {
                if (mCubesTestFragment == null) {
                    mCubesTestFragment = new CubesTestFragment();
                }
                showFragment(mCubesTestFragment);
            } else if (id.equals(MENU_CAR_SETTINGS)) {
                Intent intent = new Intent(getContext(), CarServiceSettingsActivity.class);
                getContext().startActivity(intent);
            } else if (id.equals(MENU_ORIENTATION)) {
                if (mOrientationFragment == null) {
                    mOrientationFragment = new OrientationTestFragment();
                }
                showFragment(mOrientationFragment);
            } else if (id.equals(MENU_QUIT)) {
                finish();
            }
        }

        @Override
        public void onCarMenuClosed() {
            resetTitle();
        }
    }
}
