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
import android.support.car.IAppContext;

import java.io.PrintWriter;

public class AppContextService extends IAppContext.Stub implements CarServiceBase {
    private static final int VERSION = 1;

    public AppContextService(Context context) {
        //TODO
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public void init() {
        // TODO Auto-generated method stub
    }

    @Override
    public void release() {
        // TODO Auto-generated method stub
    }

    public void handleCallStateChange(boolean callActive) {
        //TODO
    }

    @Override
    public void dump(PrintWriter writer) {
        // TODO Auto-generated method stub
    }

}
