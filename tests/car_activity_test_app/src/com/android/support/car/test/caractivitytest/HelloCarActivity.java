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

package com.android.support.car.test.caractivitytest;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.car.Car;
import android.support.car.CarNotConnectedException;
import android.support.car.ServiceConnectionListener;
import android.support.car.app.menu.CarDrawerActivity;
import android.support.car.app.menu.CarMenu;
import android.support.car.app.menu.CarMenuCallbacks;
import android.support.car.app.menu.Root;
import android.support.car.hardware.CarSensorEvent;
import android.support.car.hardware.CarSensorManager;
import android.util.Log;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class HelloCarActivity extends CarDrawerActivity {
    private static final String TAG = "HelloCarActivity";
    private TextView mTextView1;
    private TextView mTextViewDrivingStatus;
    private TextView mTextViewGear;
    private TextView mTextViewParkingBrake;
    private TextView mTextViewSpeed;
    private Car mCarApi;
    private CarSensorManager mCarSensorManager;
    private final Handler mHandler = new Handler();
    private final CarSensorManager.CarSensorEventListener mListener =
            new CarSensorManager.CarSensorEventListener() {
        @Override
        public void onSensorChanged(CarSensorEvent event) {
            switch (event.sensorType) {
                case CarSensorManager.SENSOR_TYPE_CAR_SPEED:
                    mTextViewSpeed.setText("speed:" + event.floatValues[0]);
                    break;
                case CarSensorManager.SENSOR_TYPE_GEAR:
                    mTextViewGear.setText("gear:" + event.intValues[0]);
                    break;
                case CarSensorManager.SENSOR_TYPE_PARKING_BRAKE:
                    mTextViewParkingBrake.setText("parking brake:" + event.intValues[0]);
                    break;
                case CarSensorManager.SENSOR_TYPE_DRIVING_STATUS:
                    mTextViewDrivingStatus.setText("driving status:" + event.intValues[0]);
                    break;
            }
        }
    };

    public HelloCarActivity(Proxy proxy, Context context) {
        super(proxy, context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        resetTitle();
        setScrimColor(Color.RED);
        setLightMode();
        setCarMenuCallbacks(new MyCarMenuCallbacks());
        setContentView(R.layout.hello_caractivity);
        mTextView1 = (TextView) findViewById(R.id.textView1);
        mTextViewDrivingStatus = (TextView) findViewById(R.id.textView_dring_status);
        mTextViewGear = (TextView) findViewById(R.id.textView_gear);
        mTextViewParkingBrake = (TextView) findViewById(R.id.textView_parking_brake);
        mTextViewSpeed = (TextView) findViewById(R.id.textView_speed);
        mCarApi = Car.createCar(getContext(), mServiceConnectionListener);
        // Connection to Car Service does not work for non-automotive yet.
        if (mCarApi != null) {
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
        Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.sample_apps_bg);
        setBackground(bitmap);
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
            mCarSensorManager.unregisterListener(mListener);
        }
        if (mCarApi != null) {
            mCarApi.disconnect();
        }
        Log.i(TAG, "onDestroy");
    }

    private void resetTitle() {
        setTitle(getContext().getString(R.string.app_title));
    }

    private final ServiceConnectionListener mServiceConnectionListener = new ServiceConnectionListener() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Connected to Car Service");
            try {
                mCarSensorManager = (CarSensorManager) mCarApi.getCarManager(Car.SENSOR_SERVICE);
                mCarSensorManager.registerListener(mListener,
                        CarSensorManager.SENSOR_TYPE_CAR_SPEED,
                        CarSensorManager.SENSOR_RATE_NORMAL);
                mCarSensorManager.registerListener(mListener,
                        CarSensorManager.SENSOR_TYPE_GEAR,
                        CarSensorManager.SENSOR_RATE_NORMAL);
                mCarSensorManager.registerListener(mListener,
                        CarSensorManager.SENSOR_TYPE_PARKING_BRAKE,
                        CarSensorManager.SENSOR_RATE_NORMAL);
                mCarSensorManager.registerListener(mListener,
                        CarSensorManager.SENSOR_TYPE_DRIVING_STATUS,
                        CarSensorManager.SENSOR_RATE_NORMAL);
            } catch (CarNotConnectedException e) {
                Log.e(TAG, "Car is not connected!");
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
        /** Id for the asynchronous menu */
        private static final String ASYNC = "ASYNC";
        /** Id for the checkbox menu itme */
        private static final String CHECKBOX = "checkbox";

        // for shared preferences
        /** Key used to store the checkbox state */
        private static final String KEY_CHECKED = "checked";
        /** Shared preferences name  */
        private static final String PREFERENCES = "preferences";

        @Override
        public Root onGetRoot(Bundle hints) {
            return new Root(ROOT);
        }

        @Override
        public void onLoadChildren(String parentId, CarMenu result) {
            List<CarMenu.Item> items = new ArrayList<>();
            final Bitmap cheeseBitmap = BitmapFactory.decodeResource(getResources(),
                    R.drawable.ic_cheese);
            // Demonstrate fetching the root menu.
            // It is not required to set all the fields.
            if (parentId.equals(ROOT)) {
                items.add(new CarMenu.Builder(ASYNC)
                        .setTitle("Async")
                        .setText("Send menu back asynchronously")
                        .setFlags(CarMenu.FLAG_BROWSABLE)
                        .build());
                items.add(new CarMenu.Builder(ROOT)
                        .setTitle("Will be capped after 6 clicks")
                        .setFlags(CarMenu.FLAG_BROWSABLE)
                        .build());

                boolean checked = getContext()
                        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                        .getBoolean(KEY_CHECKED, true);
                items.add(new CarMenu.Builder(CHECKBOX)
                        .setTitle("Example checkbox")
                        .setWidget(CarMenu.WIDGET_CHECKBOX)
                        .setWidgetState(checked)
                        .build());
                for (int i = 0; i < 26; ++i) {
                    String id = String.valueOf((char)('A' + i));
                    switch (i % 3) {
                        case 0:
                            // Set all the fields
                            items.add(new CarMenu.Builder(id)
                                    .setTitle(String.valueOf(id))
                                    .setText("Cheeses that start with " + id)
                                    .setFlags(CarMenu.FLAG_BROWSABLE)
                                    .setIcon(cheeseBitmap)
                                    .build());
                            break;
                        case 1:
                            // Only set title and text
                            items.add(new CarMenu.Builder(id)
                                    .setTitle(String.valueOf(id))
                                    .setText("Cheeses that start with " + id)
                                    .setFlags(CarMenu.FLAG_BROWSABLE)
                                    .build());
                            break;
                        case 2:
                            // Only set title
                            items.add(new CarMenu.Builder(id)
                                    .setTitle(String.valueOf(id))
                                    .setFlags(CarMenu.FLAG_BROWSABLE)
                                    .build());
                            break;
                    }
                }

                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        notifyChildChanged(ROOT, new CarMenu.Builder(ROOT)
                                .setIcon(cheeseBitmap)
                                .build());
                    }
                }, 5000);
            } else if (parentId.equals(ASYNC)) {
                result.detach();
                sendAsync(result);
                return;
            } else {
                boolean started = false;
                for (String cheese : Cheeses.ITEMS) {
                    if (cheese.startsWith(parentId)) {
                        if (!started) {
                            started = true;
                        }
                        items.add(new CarMenu.Builder(cheese)
                                .setTitle(cheese)
                                .setRightIcon(cheeseBitmap)
                                .build());
                    } else {
                        if (started) {
                            break;
                        }
                    }
                }
            }
            result.sendResult(items);
        }

        @Override
        public void onItemClicked(String id) {
            if (id.equals(CHECKBOX)) {
                boolean checked = getContext()
                        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                        .getBoolean(KEY_CHECKED, true);
                getContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                        .putBoolean(KEY_CHECKED, !checked)
                        .commit();
            }
            mTextView1.setText(id);
        }

        @Override
        public void onCarMenuClosed() {
        }

        private void sendAsync(final CarMenu result) {
            final List<CarMenu.Item> items = new ArrayList<>();
            items.add(new CarMenu.Builder("Test")
                    .setTitle("Appear after 5 sec")
                    .build());
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    result.sendResult(items);
                }
            }, 5000);
        }
    }

}
