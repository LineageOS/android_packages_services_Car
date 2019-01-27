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

import android.car.Car;
import android.car.CarAppFocusManager;
import android.car.CarNotConnectedException;
import android.car.hardware.CarSensorManager;
import android.car.hardware.hvac.CarHvacManager;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.property.CarPropertyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.car.drawer.CarDrawerAdapter;
import androidx.car.drawer.DrawerItemViewHolder;
import androidx.fragment.app.Fragment;

import com.android.car.apps.common.DrawerActivity;

import com.google.android.car.kitchensink.activityview.ActivityViewTestFragment;
import com.google.android.car.kitchensink.alertdialog.AlertDialogTestFragment;
import com.google.android.car.kitchensink.assistant.CarAssistantFragment;
import com.google.android.car.kitchensink.audio.AudioTestFragment;
import com.google.android.car.kitchensink.bluetooth.BluetoothHeadsetFragment;
import com.google.android.car.kitchensink.bluetooth.MapMceTestFragment;
import com.google.android.car.kitchensink.carboard.KeyboardTestFragment;
import com.google.android.car.kitchensink.cluster.InstrumentClusterFragment;
import com.google.android.car.kitchensink.connectivity.ConnectivityFragment;
import com.google.android.car.kitchensink.cube.CubesTestFragment;
import com.google.android.car.kitchensink.diagnostic.DiagnosticTestFragment;
import com.google.android.car.kitchensink.displayinfo.DisplayInfoFragment;
import com.google.android.car.kitchensink.hvac.HvacTestFragment;
import com.google.android.car.kitchensink.input.InputTestFragment;
import com.google.android.car.kitchensink.notification.NotificationFragment;
import com.google.android.car.kitchensink.orientation.OrientationTestFragment;
import com.google.android.car.kitchensink.power.PowerTestFragment;
import com.google.android.car.kitchensink.property.PropertyTestFragment;
import com.google.android.car.kitchensink.sensor.SensorsTestFragment;
import com.google.android.car.kitchensink.setting.CarServiceSettingsActivity;
import com.google.android.car.kitchensink.storagelifetime.StorageLifetimeFragment;
import com.google.android.car.kitchensink.touch.TouchTestFragment;
import com.google.android.car.kitchensink.users.UsersFragment;
import com.google.android.car.kitchensink.vhal.VehicleHalFragment;
import com.google.android.car.kitchensink.volume.VolumeTestFragment;
import com.google.android.car.kitchensink.weblinks.WebLinksTestFragment;

import java.util.Arrays;
import java.util.List;


public class KitchenSinkActivity extends DrawerActivity {
    private static final String TAG = "KitchenSinkActivity";

    private interface ClickHandler {
        void onClick();
    }

    private static abstract class MenuEntry implements ClickHandler {
        abstract String getText();
    }

    private final class OnClickMenuEntry extends MenuEntry {
        private final String mText;
        private final ClickHandler mClickHandler;

        OnClickMenuEntry(String text, ClickHandler clickHandler) {
            mText = text;
            mClickHandler = clickHandler;
        }

        @Override
        String getText() {
            return mText;
        }

        @Override
        public void onClick() {
            mClickHandler.onClick();
        }
    }

    private final class FragmentMenuEntry<T extends Fragment> extends MenuEntry {
        private final class FragmentClassOrInstance<T extends Fragment> {
            final Class<T> mClazz;
            T mFragment = null;

            FragmentClassOrInstance(Class<T> clazz) {
                mClazz = clazz;
            }

            T getFragment() {
                if (mFragment == null) {
                    try {
                        mFragment = mClazz.newInstance();
                    } catch (InstantiationException | IllegalAccessException e) {
                        Log.e(TAG, "unable to create fragment", e);
                    }
                }
                return mFragment;
            }
        }

        private final String mText;
        private final FragmentClassOrInstance<T> mFragment;

        FragmentMenuEntry(String text, Class<T> clazz) {
            mText = text;
            mFragment = new FragmentClassOrInstance<>(clazz);
        }

        @Override
        String getText() {
            return mText;
        }

        @Override
        public void onClick() {
            Fragment fragment = mFragment.getFragment();
            if (fragment != null) {
                KitchenSinkActivity.this.showFragment(fragment);
            } else {
                Log.e(TAG, "cannot show fragment for " + getText());
            }
        }
    }

    private final List<MenuEntry> mMenuEntries = Arrays.asList(
            new FragmentMenuEntry("activity view", ActivityViewTestFragment.class),
            new FragmentMenuEntry("alert window", AlertDialogTestFragment.class),
            new FragmentMenuEntry("assistant", CarAssistantFragment.class),
            new FragmentMenuEntry("audio", AudioTestFragment.class),
            new FragmentMenuEntry("bluetooth headset", BluetoothHeadsetFragment.class),
            new FragmentMenuEntry("bluetooth messaging test", MapMceTestFragment.class),
            new OnClickMenuEntry("car service settings", () -> {
                Intent intent = new Intent(KitchenSinkActivity.this,
                        CarServiceSettingsActivity.class);
                startActivity(intent);
            }),
            new FragmentMenuEntry("carboard", KeyboardTestFragment.class),
            new FragmentMenuEntry("connectivity", ConnectivityFragment.class),
            new FragmentMenuEntry("cubes test", CubesTestFragment.class),
            new FragmentMenuEntry("diagnostic", DiagnosticTestFragment.class),
            new FragmentMenuEntry("display info", DisplayInfoFragment.class),
            new FragmentMenuEntry("hvac", HvacTestFragment.class),
            new FragmentMenuEntry("inst cluster", InstrumentClusterFragment.class),
            new FragmentMenuEntry("input test", InputTestFragment.class),
            new FragmentMenuEntry("notification", NotificationFragment.class),
            new FragmentMenuEntry("orientation test", OrientationTestFragment.class),
            new FragmentMenuEntry("power test", PowerTestFragment.class),
            new FragmentMenuEntry("property test", PropertyTestFragment.class),
            new FragmentMenuEntry("sensors", SensorsTestFragment.class),
            new FragmentMenuEntry("storage lifetime", StorageLifetimeFragment.class),
            new FragmentMenuEntry("touch test", TouchTestFragment.class),
            new FragmentMenuEntry("users", UsersFragment.class),
            new FragmentMenuEntry("volume test", VolumeTestFragment.class),
            new FragmentMenuEntry("vehicle hal", VehicleHalFragment.class),
            new FragmentMenuEntry("web links", WebLinksTestFragment.class),
            new OnClickMenuEntry("quit", KitchenSinkActivity.this::finish)
    );

    private Car mCarApi;
    private CarHvacManager mHvacManager;
    private CarPowerManager mPowerManager;
    private CarPropertyManager mPropertyManager;
    private CarSensorManager mSensorManager;
    private CarAppFocusManager mCarAppFocusManager;
    private Object mPropertyManagerReady = new Object();

    public CarHvacManager getHvacManager() {
        return mHvacManager;
    }

    public CarPowerManager getPowerManager() {
        return mPowerManager;
    }

    public CarPropertyManager getPropertyManager() {
        return mPropertyManager;
    }

    public CarSensorManager getSensorManager() {
        return mSensorManager;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.kitchen_content);

        // Connection to Car Service does not work for non-automotive yet.
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            initCarApi();
        }
        Log.i(TAG, "onCreate");
        getDrawerController().setRootAdapter(new DrawerAdapter());
    }

    private void initCarApi() {
        if (mCarApi != null && mCarApi.isConnected()) {
            mCarApi.disconnect();
            mCarApi = null;
        }
        mCarApi = Car.createCar(this, mServiceConnection);
        mCarApi.connect();
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
        if (mCarApi != null) {
            mCarApi.disconnect();
        }
        Log.i(TAG, "onDestroy");
    }

    private void showFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.kitchen_content, fragment)
                .commit();
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Connected to Car Service");
            synchronized (mPropertyManagerReady) {
                try {
                    mHvacManager = (CarHvacManager) mCarApi.getCarManager(
                            android.car.Car.HVAC_SERVICE);
                    mPowerManager = (CarPowerManager) mCarApi.getCarManager(
                            android.car.Car.POWER_SERVICE);
                    mPropertyManager = (CarPropertyManager) mCarApi.getCarManager(
                            android.car.Car.PROPERTY_SERVICE);
                    mSensorManager = (CarSensorManager) mCarApi.getCarManager(
                            android.car.Car.SENSOR_SERVICE);
                    mCarAppFocusManager =
                            (CarAppFocusManager) mCarApi.getCarManager(Car.APP_FOCUS_SERVICE);
                    mPropertyManagerReady.notifyAll();
                } catch (CarNotConnectedException e) {
                    Log.e(TAG, "Car is not connected!", e);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Disconnect from Car Service");
        }
    };

    public Car getCar() {
        return mCarApi;
    }

    private final class DrawerAdapter extends CarDrawerAdapter {

        public DrawerAdapter() {
            super(KitchenSinkActivity.this, true /* showDisabledOnListOnEmpty */);
            setTitle(getString(R.string.app_title));
        }

        @Override
        protected int getActualItemCount() {
            return mMenuEntries.size();
        }

        @Override
        protected void populateViewHolder(DrawerItemViewHolder holder, int position) {
            holder.getTitleView().setText(mMenuEntries.get(position).getText());
            holder.itemView.setOnClickListener(v -> onItemClick(holder.getAdapterPosition()));
        }

        private void onItemClick(int position) {
            if ((position < 0) || (position >= mMenuEntries.size())) {
                Log.wtf(TAG, "Unknown menu item: " + position);
                return;
            }

            mMenuEntries.get(position).onClick();

            getDrawerController().closeDrawer();
        }
    }

    // Use AsyncTask to refresh Car*Manager after car service connected
    public void requestRefreshManager(final Runnable r, final Handler h) {
        final AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... unused) {
                synchronized (mPropertyManagerReady) {
                    while (!mCarApi.isConnected()) {
                        try {
                            mPropertyManagerReady.wait();
                        } catch (InterruptedException e) {
                            return null;
                        }
                    }
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void unused) {
                h.post(r);
            }
        };
        task.execute();
    }
}
