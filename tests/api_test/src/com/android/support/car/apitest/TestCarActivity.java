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

package com.android.support.car.apitest;

import android.content.Context;
import android.os.Bundle;
import android.support.car.Car;
import android.support.car.CarNotConnectedException;
import android.support.car.CarNotSupportedException;
import android.support.car.app.CarActivity;
import android.util.Log;

public class TestCarActivity extends CarActivity {
    private static final String TAG = TestCarActivity.class.getSimpleName();

    public static volatile TestAction<TestCarActivity> sCreateTestAction;
    public static volatile TestAction<TestCarActivity> sStartTestAction;
    public static volatile TestAction<TestCarActivity> sResumeTestAction;
    public static volatile TestAction<TestCarActivity> sPauseTestAction;
    public static volatile TestAction<TestCarActivity> sStopTestAction;
    public static volatile TestAction<TestCarActivity> sDestroyTestAction;

    public TestCarActivity(CarActivity.Proxy proxy, Context context, Car carApi) {
        super(proxy, context, carApi);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) throws CarNotConnectedException {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        Log.d(TAG, "TestAction " + sCreateTestAction);
        doRunTest(sCreateTestAction);
    }


    @Override
    protected void onStart() throws CarNotConnectedException {
        Log.d(TAG, "onStart");
        super.onStart();
        doRunTest(sStartTestAction);
    }

    @Override
    protected void onResume() throws CarNotConnectedException {
        Log.d(TAG, "onResume");
        super.onResume();
        doRunTest(sResumeTestAction);

    }

    @Override
    protected void onPause() throws CarNotConnectedException {
        Log.d(TAG, "onPause");
        super.onPause();
        doRunTest(sPauseTestAction);
    }


    @Override
    protected void onStop() throws CarNotConnectedException {
        Log.d(TAG, "onStop");
        super.onStop();
        doRunTest(sStopTestAction);
    }

    @Override
    protected void onDestroy() throws CarNotConnectedException {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        doRunTest(sDestroyTestAction);
    }

    private void doRunTest(TestAction<TestCarActivity> test) {
        Log.d(TAG, "doRunTest " + test);
        if (test != null) {
            test.doRun(this);
        }
    }

    public static void testCleanup() {
        sCreateTestAction = null;
        sStartTestAction = null;
        sResumeTestAction = null;
        sPauseTestAction = null;
        sStopTestAction = null;
        sDestroyTestAction = null;
    }
}
