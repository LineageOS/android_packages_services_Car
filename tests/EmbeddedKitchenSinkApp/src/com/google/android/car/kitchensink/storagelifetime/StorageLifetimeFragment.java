/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.google.android.car.kitchensink.storagelifetime;

import android.annotation.Nullable;
import android.car.Car;
import android.car.storagemonitoring.CarStorageMonitoringManager;
import android.car.storagemonitoring.WearEstimateChange;
import android.database.DataSetObserver;
import android.graphics.Color;
import android.os.Bundle;
import android.os.StatFs;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public class StorageLifetimeFragment extends Fragment {
    private static final String FILE_NAME = "storage.bin";
    private static final String TAG = "CAR.STORAGELIFETIME.KS";

    private static final int KILOBYTE = 1024;
    private static final int MEGABYTE = 1024 * 1024;

    private StatFs mStatFs;
    private KitchenSinkActivity mActivity;
    private TextView mStorageWearInfo;
    private ListView mStorageChangesHistory;
    private TextView mFreeSpaceInfo;
    private CarStorageMonitoringManager mStorageManager;

    // TODO(egranata): put this somewhere more useful than KitchenSink
    private static String preEolToString(int preEol) {
        switch (preEol) {
            case 1: return "normal";
            case 2: return "warning";
            case 3: return "urgent";
            default:
                return "unknown";
        }
    }

    private void writeBytesToFile(int size) {
        try {
            byte[] data = new byte[size];
            SecureRandom.getInstanceStrong().nextBytes(data);
            Path filePath = new File(mActivity.getFilesDir(), FILE_NAME).toPath();
            if (Files.notExists(filePath)) {
                Files.createFile(filePath);
            }
            Files.write(filePath,
                data,
                StandardOpenOption.APPEND);
        } catch (NoSuchAlgorithmException | IOException e) {
            Log.w(TAG, "could not append data", e);
        }
    }

    @Nullable
    @Override
    public View onCreateView(
            LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.storagewear, container, false);
        mActivity = (KitchenSinkActivity) getHost();
        mStorageWearInfo = view.findViewById(R.id.storage_wear_info);
        mStorageChangesHistory = view.findViewById(R.id.storage_events_list);
        mFreeSpaceInfo = view.findViewById(R.id.free_disk_space);

        view.findViewById(R.id.write_one_kilobyte).setOnClickListener(
            v -> writeBytesToFile(KILOBYTE));

        view.findViewById(R.id.write_one_megabyte).setOnClickListener(
            v -> writeBytesToFile(MEGABYTE));

        return view;
    }

    private void reloadInfo() {
        try {
            mStatFs = new StatFs(mActivity.getFilesDir().getAbsolutePath());

            mStorageManager =
                (CarStorageMonitoringManager) mActivity.getCar().getCarManager(
                        Car.STORAGE_MONITORING_SERVICE);

            mStorageWearInfo.setText("Wear estimate: " +
                mStorageManager.getWearEstimate() + "\nPre EOL indicator: " +
                preEolToString(mStorageManager.getPreEolIndicatorStatus()));

            mStorageChangesHistory.setAdapter(new ArrayAdapter(mActivity,
                    R.layout.wear_estimate_change_textview,
                    mStorageManager.getWearEstimateHistory().toArray()));

            mFreeSpaceInfo.setText("Available blocks: " + mStatFs.getAvailableBlocksLong() +
                "\nBlock size: " + mStatFs.getBlockSizeLong() + " bytes" +
                "\nfor a total free space of: " +
                (mStatFs.getBlockSizeLong() * mStatFs.getAvailableBlocksLong() / MEGABYTE) + "MB");
        } catch (android.car.CarNotConnectedException|
                 android.support.car.CarNotConnectedException e) {
            Log.e(TAG, "Car not connected or not supported", e);
        }

    }

    @Override
    public void onResume() {
        super.onResume();
        reloadInfo();
    }
}
