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

import android.content.Context;
import android.support.car.content.pm.CarPackageManager;
import android.support.car.content.pm.ICarPackageManager;

import java.io.PrintWriter;

public class CarPackageManagerService extends ICarPackageManager.Stub implements CarServiceBase {
    private static final int VERSION = 1;

    public CarPackageManagerService(Context context) {
        //TODO
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public void init() {
        //TODO
    }

    @Override
    public void release() {
        //TODO
    }

    @Override
    public void dump(PrintWriter writer) {
        //TODO
    }
}
