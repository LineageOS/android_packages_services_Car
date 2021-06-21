/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.telemetry.publisher;

import com.android.car.telemetry.databroker.DataSubscriber;

/**
 * Publisher for Android logs (adb logcat).
 *
 * TODO(b/187525360): Move the logic from LogcatReader here.
 */
public class LogcatPublisher implements Publisher {
    @Override
    public void addSubscriber(DataSubscriber dataSubscriber) {
        // TODO(b/187525360): implement
    }

    @Override
    public void removeSubscriber(DataSubscriber dataSubscriber) {
        // TODO(b/187525360): implement
    }
}
