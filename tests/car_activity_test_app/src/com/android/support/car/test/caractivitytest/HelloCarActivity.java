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

import android.content.Context;
import android.os.Bundle;
import android.support.car.Car;
import android.support.car.CarNotConnectedException;
import android.support.car.CarNotSupportedException;
import android.support.car.app.CarActivity;
import android.support.car.hardware.CarSensorEvent;
import android.support.car.hardware.CarSensorManager;
import android.support.car.hardware.CarSensorManager.CarSensorEventListener;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

public class HelloCarActivity extends CarActivity {
    private static final String TAG = HelloCarActivity.class.getSimpleName();
    private TextView mTextView1;
    private Button mButton1;
    private TextView mTextViewDrivingStatus;
    private TextView mTextViewGear;
    private TextView mTextViewParkingBrake;
    private TextView mTextViewSpeed;
    private int mClickCount = 0;
    private CarSensorManager mCarSensorManager;
    private final CarSensorEventListener mListener = new CarSensorEventListener() {

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

    public HelloCarActivity(Proxy proxy, Context context, Car carApi) {
        super(proxy, context, carApi);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
            throws CarNotConnectedException {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.hello_caractivity);
        mTextView1 = (TextView) findViewById(R.id.textView1);
        mButton1 = (Button) findViewById(R.id.button1);
        mButton1.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View view) {
                mClickCount++;
                mTextView1.setText("You clicked :" + mClickCount);
            }
        });
        mTextViewDrivingStatus = (TextView) findViewById(R.id.textView_dring_status);
        mTextViewGear = (TextView) findViewById(R.id.textView_gear);
        mTextViewParkingBrake = (TextView) findViewById(R.id.textView_parking_brake);
        mTextViewSpeed = (TextView) findViewById(R.id.textView_speed);
        mCarSensorManager = (CarSensorManager) getCarApi().getCarManager(Car.SENSOR_SERVICE);
        mCarSensorManager.registerListener(mListener, CarSensorManager.SENSOR_TYPE_CAR_SPEED,
                CarSensorManager.SENSOR_RATE_NORMAL);
        mCarSensorManager.registerListener(mListener, CarSensorManager.SENSOR_TYPE_GEAR,
                CarSensorManager.SENSOR_RATE_NORMAL);
        mCarSensorManager.registerListener(mListener, CarSensorManager.SENSOR_TYPE_PARKING_BRAKE,
                CarSensorManager.SENSOR_RATE_NORMAL);
        mCarSensorManager.registerListener(mListener, CarSensorManager.SENSOR_TYPE_DRIVING_STATUS,
                CarSensorManager.SENSOR_RATE_NORMAL);
        Log.i(TAG, "onCreate");
    }

    @Override
    protected void onStart() throws CarNotConnectedException {
        super.onStart();
        Log.i(TAG, "onStart");
    }

    @Override
    protected void onRestart() throws CarNotConnectedException {
        super.onRestart();
        Log.i(TAG, "onRestart");
    }

    @Override
    protected void onResume() throws CarNotConnectedException {
        super.onResume();
        Log.i(TAG, "onResume");
    }

    @Override
    protected void onPause() throws CarNotConnectedException {
        super.onPause();
        Log.i(TAG, "onPause");
    }

    @Override
    protected void onStop() throws CarNotConnectedException {
        super.onStop();
        Log.i(TAG, "onStop");
    }

    @Override
    protected void onDestroy() throws CarNotConnectedException {
        super.onDestroy();
        mCarSensorManager.unregisterListener(mListener);
        Log.i(TAG, "onDestroy");
    }

}
