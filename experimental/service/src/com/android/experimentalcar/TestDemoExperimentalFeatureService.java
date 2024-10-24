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

package com.android.experimentalcar;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.car.experimental.ITestDemoExperimental;
import android.util.proto.ProtoOutputStream;

import com.android.car.CarServiceBase;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;

/**
 * Demo service for testing experimental feature.
 */
public final class TestDemoExperimentalFeatureService extends ITestDemoExperimental.Stub
        implements CarServiceBase {

    @Override
    public void init() {
        // Nothing to do
    }

    @Override
    public void release() {
        // Nothing to do
    }

    @Override
    public void dump(IndentingPrintWriter writer) {
        writer.println("*TestExperimentalFeatureService*");
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dumpProto(ProtoOutputStream proto) {}

    @Override
    public String ping(String msg) {
        return msg;
    }
}
