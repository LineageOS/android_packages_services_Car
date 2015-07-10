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

package com.android.support.car.test.caractivitytest;

import android.content.Context;
import android.os.Bundle;
import android.support.car.Car;
import android.support.car.CarNotConnectedException;
import android.support.car.CarNotSupportedException;
import android.support.car.app.CarActivity;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

public class HelloCarActivity extends CarActivity {
    private static final String TAG = HelloCarActivity.class.getSimpleName();
    private TextView mTextView1;
    private Button mButton1;
    private int mClickCount = 0;

    public HelloCarActivity(Proxy proxy, Context context, Car carApi) {
        super(proxy, context, carApi);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
            throws CarNotConnectedException {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.hello_caractivity);
        mTextView1 = (TextView) findViewById(R.id.textView1);
        mButton1 = (Button) findViewById(R.id.button1);
        mButton1.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View view) {
                mClickCount++;
                mTextView1.setText("You clicked :" + mClickCount);
            }
        });
        Log.i(TAG, "onCreate");
    }

    @Override
    protected void onStart() throws CarNotConnectedException {
        super.onStart();
        Log.i(TAG, "onStart");
    }

    @Override
    protected void onRestart() throws CarNotConnectedException {
        super.onRestart();
        Log.i(TAG, "onRestart");
    }

    @Override
    protected void onResume() throws CarNotConnectedException {
        super.onResume();
        Log.i(TAG, "onResume");
    }

    @Override
    protected void onPause() throws CarNotConnectedException {
        super.onPause();
        Log.i(TAG, "onPause");
    }

    @Override
    protected void onStop() throws CarNotConnectedException {
        super.onStop();
        Log.i(TAG, "onStop");
    }

    @Override
    protected void onDestroy() throws CarNotConnectedException {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
    }

}
