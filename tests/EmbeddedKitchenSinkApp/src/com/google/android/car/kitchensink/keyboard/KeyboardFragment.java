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
package com.google.android.car.kitchensink.keyboard;

import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.car.Car;
import android.support.car.CarNotConnectedException;
import android.support.car.app.menu.CarDrawerActivity;
import android.support.car.app.menu.SearchBoxEditListener;
import android.support.car.hardware.CarSensorEvent;
import android.support.car.hardware.CarSensorManager;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.car.kitchensink.R;

public class KeyboardFragment extends Fragment {
    private static final String TAG = "KitchenSinkKeyboard";
    public static final int CARD = 0xfffafafa;
    public static final int TEXT_PRIMARY_DAY = 0xde000000;
    public static final int TEXT_SECONDARY_DAY = 0x8a000000;

    private TextView mDrivingStatus;
    private Button mImeButton;
    private Button mCloseImeButton;
    private Button mShowHideInputButton;
    private CarDrawerActivity mActivity;
    private TextView mOnSearchText;
    private TextView mOnEditText;
    private CarSensorManager mSensorManager;

    private final Handler mHandler = new Handler();

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.keyboard_test, container, false);
        mActivity = (CarDrawerActivity) getHost();
        mImeButton = (Button) v.findViewById(R.id.ime_button);
        mImeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mActivity.startInput("Hint");
            }
        });

        mCloseImeButton = (Button) v.findViewById(R.id.stop_ime_button);
        mCloseImeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mActivity.stopInput();
                resetInput();
            }
        });

        mShowHideInputButton = (Button) v.findViewById(R.id.ime_button2);
        mShowHideInputButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mActivity.isShowingSearchBox()) {
                    mActivity.hideSearchBox();
                } else {
                    resetInput();
                }
            }
        });

        mOnSearchText = (TextView) v.findViewById(R.id.search_text);
        mOnEditText = (TextView) v.findViewById(R.id.edit_text);
        resetInput();
        mActivity.setSearchBoxEndView(View.inflate(getContext(), R.layout.keyboard_end_view, null));
        mDrivingStatus = (TextView) v.findViewById(R.id.driving_status);
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
            mSensorManager = (CarSensorManager)
                    mActivity.getCar().getCarManager(Car.SENSOR_SERVICE);
            mSensorManager.addListener(mOnSensorChangedListener,
                    CarSensorManager.SENSOR_TYPE_DRIVING_STATUS,
                    CarSensorManager.SENSOR_RATE_FASTEST);
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car not connected or not supported", e);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mSensorManager != null) {
            try {
                mSensorManager.removeListener(mOnSensorChangedListener);
            } catch (CarNotConnectedException e) {
                Log.e(TAG, "Car not connected", e);
            }
        }
    }

    private final CarSensorManager.OnSensorChangedListener mOnSensorChangedListener =
            new CarSensorManager.OnSensorChangedListener() {
                @Override
                public void onSensorChanged(CarSensorManager manager, CarSensorEvent event) {
                    if (event.sensorType != CarSensorManager.SENSOR_TYPE_DRIVING_STATUS) {
                        return;
                    }
                    int drivingStatus = event.getDrivingStatusData().status;

                    boolean keyboardEnabled =
                            (drivingStatus & CarSensorEvent.DRIVE_STATUS_NO_KEYBOARD_INPUT) == 0;
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mDrivingStatus.setText("Driving status: " + drivingStatus
                                    + " Keyboard " + (keyboardEnabled ? "enabled" : "disabled"));
                        }
                    });
                }
            };

    private void resetInput() {
        mActivity.showSearchBox(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mActivity.startInput("Hint");
            }
        });
        mActivity.setSearchBoxEditListener(mEditListener);
        mActivity.setSearchBoxColors(CARD, TEXT_SECONDARY_DAY,
                TEXT_PRIMARY_DAY, TEXT_SECONDARY_DAY);
    }


    private final SearchBoxEditListener mEditListener = new SearchBoxEditListener() {
        @Override
        public void onSearch(final String text) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mOnSearchText.setText("Search: " + text);
                    resetInput();
                }
            });
        }

        @Override
        public void onEdit(final String text) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mOnEditText.setText("Edit: " + text);
                }
            });
        }
    };
}
