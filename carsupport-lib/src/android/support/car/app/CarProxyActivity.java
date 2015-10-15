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

package android.support.car.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.support.car.Car;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;

import com.android.internal.annotations.GuardedBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedList;

/**
 * android Activity controlling / proxying {@link CarActivity}. Applications should have its own
 * {@link android.app.Activity} overriding only constructor.
 */
public class CarProxyActivity extends Activity {
    private final Class mCarActivityClass;
    // no synchronization, but main thread only
    private CarActivity mCarActivity;

    private final CarActivity.Proxy mCarActivityProxy = new CarActivity.Proxy() {
        @Override
        public void setContentView(View view) {
            CarProxyActivity.this.setContentView(view);
        }

        @Override
        public void setContentView(int layoutResID) {
            CarProxyActivity.this.setContentView(layoutResID);
        }

        @Override
        public View findViewById(int id) {
            return CarProxyActivity.this.findViewById(id);
        }

        @Override
        public Resources getResources() {
            return CarProxyActivity.this.getResources();
        }

        @Override
        public void finish() {
            CarProxyActivity.this.finish();
        }

        @Override
        public LayoutInflater getLayoutInflater() {
            return CarProxyActivity.this.getLayoutInflater();
        }
    };

    public CarProxyActivity(Class carActivityClass) {
        mCarActivityClass = carActivityClass;
    }

    private void createCarActivity() {
        Constructor<?> ctor;
        try {
            ctor = mCarActivityClass.getDeclaredConstructor(CarActivity.Proxy.class, Context.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Cannot construct given CarActivity, no constructor for " +
                    mCarActivityClass.getName(), e);
        }
        try {
            mCarActivity = (CarActivity) ctor.newInstance(mCarActivityProxy, this);
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException e) {
            throw new RuntimeException("Cannot construct given CarActivity, constructor failed for "
                    + mCarActivityClass.getName(), e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        createCarActivity();
        super.onCreate(savedInstanceState);
        mCarActivity.dispatchCmd(CarActivity.CMD_ON_CREATE, savedInstanceState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mCarActivity.dispatchCmd(CarActivity.CMD_ON_START, null);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        mCarActivity.dispatchCmd(CarActivity.CMD_ON_RESTART, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mCarActivity.dispatchCmd(CarActivity.CMD_ON_RESUME, null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mCarActivity.dispatchCmd(CarActivity.CMD_ON_PAUSE, null);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mCarActivity.dispatchCmd(CarActivity.CMD_ON_STOP, null);
    }

    @Override
    public void onBackPressed() {
        mCarActivity.dispatchCmd(CarActivity.CMD_ON_BACK_PRESSED, null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mCarActivity.dispatchCmd(CarActivity.CMD_ON_DESTROY, null);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mCarActivity.dispatchCmd(CarActivity.CMD_ON_SAVE_INSTANCE_STATE, outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedState) {
        super.onRestoreInstanceState(savedState);
        mCarActivity.dispatchCmd(CarActivity.CMD_ON_RESTORE_INSTANCE_STATE, savedState);
    }
}
