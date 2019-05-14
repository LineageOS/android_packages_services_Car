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

import android.car.ICarBugreportCallback;

/**
 * Binder interface for {@link android.car.CarBugreportManager}.
 *
 * @hide
 */
 interface ICarBugreportService {

    /**
     * Starts bugreport service to capture a bugreport.
     * This method will be removed once all the clients transition to the new API.
     * @deprecated
     */
    void requestBugreport(in ParcelFileDescriptor pfd, ICarBugreportCallback callback) = 1;

    /**
     * Starts bugreport service to capture a zipped bugreport. The caller needs to provide
     * two file descriptors. The "output" file descriptor will be used to provide the actual
     * zip file and the "progress" descriptor will be used to provide the progress information.
     * Both of these descriptors are written by the service and will be read by the client.
     *
     * The progress protocol is described
     * <a href="https://android.googlesource.com/platform/frameworks/native/+/master/cmds/bugreportz/readme.md">
     *     here</a>
     */
    void requestZippedBugreport(in ParcelFileDescriptor output, in ParcelFileDescriptor progress,
        ICarBugreportCallback callback) = 2;
 }

