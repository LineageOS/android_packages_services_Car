/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.google.android.car.uxr.sample;

import static android.os.SystemClock.elapsedRealtimeNanos;

import android.annotation.DrawableRes;
import android.app.Activity;
import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import androidx.car.widget.ListItem;
import androidx.car.widget.ListItemAdapter;
import androidx.car.widget.ListItemProvider;
import androidx.car.widget.PagedListView;
import androidx.car.widget.TextListItem;

/**
 * Sample app that uses components in car support library to demonstrate Car drivingstate UXR status.
 */
public class MainActivity extends Activity {
    public static final String TAG = "drivingstate";

    private Car mCar;
    private CarUxRestrictionsManager mCarUxRestrictionsManager;
    private TextView mUxrStatus;
    private Button mToggleButton;
    private PagedListView mPagedListView;

    private final ServiceConnection mCarConnectionListener =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder iBinder) {
                    Log.d(TAG, "Connected to " + name.flattenToString());
                    // Get a UXR manager
                    try {
                        mCarUxRestrictionsManager = (CarUxRestrictionsManager) mCar.getCarManager(
                                        Car.CAR_UX_RESTRICTION_SERVICE);

                    } catch (CarNotConnectedException e) {
                        Log.e(TAG, "Failed to get a connection", e);
                    }

                    // Register listener
                    try {
                        mCarUxRestrictionsManager.registerListener(uxrChangeListener);
                    } catch (CarNotConnectedException e) {
                        e.printStackTrace();
                    }

                    // Show current status
                    try {
                        updateWidgetText(mCarUxRestrictionsManager.getCurrentCarUxRestrictions());
                    } catch (CarNotConnectedException e) {
                        e.printStackTrace();
                    }

                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.d(TAG, "Disconnected from " + name.flattenToString());
                    try {
                        mCarUxRestrictionsManager.unregisterListener();
                    } catch (CarNotConnectedException e) {
                        e.printStackTrace();
                    }

                    mCar = null;
                    mCarUxRestrictionsManager = null;
                }
            };

    private void updateWidgetText(CarUxRestrictions restrictions) {
        mToggleButton.setText(
                restrictions.isRequiresDistractionOptimization()
                        ? "Switch to Park" : "Switch to Drive");
        mUxrStatus.setText(
                restrictions.isRequiresDistractionOptimization()
                        ? "Requires Distraction Optimization" : "No restriction");

        mToggleButton.requestLayout();
        mUxrStatus.requestLayout();
    }

    private CarUxRestrictionsManager.onUxRestrictionsChangedListener uxrChangeListener = restrictions -> {
        updateWidgetText(restrictions);
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        mUxrStatus = findViewById(R.id.uxr_status);
        mToggleButton = findViewById(R.id.toggle_status);
        mPagedListView = findViewById(R.id.paged_list_view);

        setUpPagedListView();

        final boolean[] requiresDO = {false};
        mToggleButton.setOnClickListener(v -> {
            // Create a mock UXR change.
            requiresDO[0] = !requiresDO[0];
            CarUxRestrictions restrictions = new CarUxRestrictions(
                    requiresDO[0],
                    requiresDO[0]
                            ? CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED
                            : CarUxRestrictions.UX_RESTRICTIONS_BASELINE,
                    elapsedRealtimeNanos());
            updateWidgetText(restrictions);
        });

        // Connect to car service
        mCar = Car.createCar(this, mCarConnectionListener);
        mCar.connect();
    }

    private void setUpPagedListView() {
        ListItemAdapter adapter = new ListItemAdapter(this, populateData());
        mPagedListView.setAdapter(adapter);
    }

    private ListItemProvider populateData() {
        List<ListItem> items = new ArrayList<>();
        items.add(createMessage(android.R.drawable.ic_menu_myplaces, "alice",
                "i have a really important message but it may hinder your ability to drive. "));

        items.add(createMessage(android.R.drawable.ic_menu_myplaces, "bob",
                "hey this is a really long message that i have always wanted to say. but before " +
                        "saying it i feel it's only appropriate if i lay some groundwork for it. "));
        items.add(createMessage(android.R.drawable.ic_menu_myplaces, "mom",
                "i think you are the best. i think you are the best. i think you are the best. " +
                        "i think you are the best. i think you are the best. i think you are the best. " +
                        "i think you are the best. i think you are the best. i think you are the best. " +
                        "i think you are the best. i think you are the best. i think you are the best. " +
                        "i think you are the best. i think you are the best. i think you are the best. " +
                        "i think you are the best. i think you are the best. "));
        items.add(createMessage(android.R.drawable.ic_menu_myplaces, "john", "hello world"));
        items.add(createMessage(android.R.drawable.ic_menu_myplaces, "jeremy",
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor " +
                        "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, " +
                        "quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo " +
                        "consequat. Duis aute irure dolor in reprehenderit in voluptate velit " +
                        "esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat " +
                        "cupidatat non proident, sunt in culpa qui officia deserunt mollit " +
                        "anim id est laborum."));
        return new ListItemProvider.ListProvider(items);
    }

    private TextListItem createMessage(@DrawableRes int profile, String contact, String message) {
        TextListItem item = new TextListItem(this);
        item.setPrimaryActionIcon(profile, false /* useLargeIcon */);
        item.setTitle(contact);
        item.setBody(message);
        item.setSupplementalIcon(android.R.drawable.stat_notify_chat, false);
        return item;
    }

    @Override
    protected void onDestroy() {
        if (mCar != null) {
            mCar.disconnect();
        }
    }
}

