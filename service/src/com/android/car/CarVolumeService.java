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

package com.android.car;

import android.car.media.CarAudioManager;
import android.content.Context;
import android.hardware.automotive.vehicle.V2_0.VehicleAudioContextFlag;
import android.media.IVolumeController;

import com.android.car.hal.AudioHalService;

import java.io.PrintWriter;

/**
 * Handles car volume controls.
 *
 * It delegates to a {@link com.android.car.CarVolumeController} to do proper
 * volume controls based on different car properties.
 *
 * @hide
 */
public class CarVolumeService {
    private static final String TAG = "CarVolumeService";

    public static int DEFAULT_CAR_AUDIO_CONTEXT =
            VehicleAudioContextFlag.MUSIC_FLAG;
    private final Context mContext;
    private final AudioHalService mAudioHal;
    private final CarAudioService mAudioService;
    private final CarInputService mInputService;

    private CarVolumeController mCarVolumeController;

    private IVolumeController mIVolumeController;

    public CarVolumeService(Context context, CarAudioService audioService, AudioHalService audioHal,
                            CarInputService inputService) {
        mContext = context;
        mAudioHal = audioHal;
        mAudioService = audioService;
        mInputService = inputService;
    }

    public synchronized void init() {
        mCarVolumeController = new CarVolumeController(mContext,
                mAudioService, mAudioHal, mInputService);

        mCarVolumeController.init();
        if (mIVolumeController != null) {
            mCarVolumeController.setVolumeController(mIVolumeController);
        }
        mInputService.setVolumeKeyListener(mCarVolumeController);
    }

    public synchronized void release() {
        mCarVolumeController.release();
    }

    public void setUsageVolume(@CarAudioManager.CarAudioUsage int carUsage, int index, int flags) {
        getController().setUsageVolume(carUsage, index, flags);
    }

    public synchronized void setVolumeController(IVolumeController controller) {
        mIVolumeController = controller;
        getController().setVolumeController(controller);
    }

    public int getUsageMaxVolume(@CarAudioManager.CarAudioUsage int carUsage) {
        return getController().getUsageMaxVolume(carUsage);
    }

    public int getUsageMinVolume(@CarAudioManager.CarAudioUsage int carUsage) {
        return getController().getUsageMinVolume(carUsage);
    }

    public int getUsageVolume(@CarAudioManager.CarAudioUsage int carUsage) {
        return getController().getUsageVolume(carUsage);
    }

    public void dump(PrintWriter writer) {
        mCarVolumeController.dump(writer);
    }

    private synchronized CarVolumeController getController() {
        return mCarVolumeController;
    }
}
