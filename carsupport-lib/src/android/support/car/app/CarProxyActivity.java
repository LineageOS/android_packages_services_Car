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
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.support.car.Car;
import android.support.car.ServiceConnectionListener;
import android.util.Log;
import android.util.Pair;
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
    private Car mCarApi;
    // no synchronization, but main thread only
    private CarActivity mCarActivity;
    @GuardedBy("this")
    private boolean mConnected = false;
    @GuardedBy("this")
    private final LinkedList<Pair<Integer, Object>> mCmds = new LinkedList<Pair<Integer, Object>>();
    private final ServiceConnectionListener mConnectionListener= new ServiceConnectionListener() {

        @Override
        public void onServiceSuspended(int cause) {
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }

        @Override
        public void onServiceConnectionFailed(int cause) {
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (this) {
                mConnected = true;
                for (Pair<Integer, Object> cmd: mCmds) {
                    mCarActivity.dispatchCmd(cmd.first, cmd.second);
                }
                mCmds.clear();
            }
        }
    };

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
        public void finish() {
            CarProxyActivity.this.finish();
        }

    };

    public CarProxyActivity(Class carActivityClass) {
        mCarActivityClass = carActivityClass;
    }

    private void createCarActivity() {
        mCarApi = new Car(this, mConnectionListener, null);
        mCarApi.connect();
        Constructor<?> ctor;
        try {
            ctor = mCarActivityClass.getDeclaredConstructor(CarActivity.Proxy.class, Context.class,
                    Car.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Cannot construct given CarActivity, no constructor for " +
                    mCarActivityClass.getName(), e);
        }
        try {
            mCarActivity = (CarActivity) ctor.newInstance(mCarActivityProxy, this, mCarApi);
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException e) {
            throw new RuntimeException("Cannot construct given CarActivity, constructor failed for "
                    + mCarActivityClass.getName(), e);
        }
    }

    private synchronized boolean isConnected() {
        return mConnected;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        createCarActivity();
        super.onCreate(savedInstanceState);
        handleCmd(CarActivity.CMD_ON_CREATE, savedInstanceState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        handleCmd(CarActivity.CMD_ON_START, null);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        handleCmd(CarActivity.CMD_ON_RESTART, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleCmd(CarActivity.CMD_ON_RESUME, null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handleCmd(CarActivity.CMD_ON_PAUSE, null);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handleCmd(CarActivity.CMD_ON_STOP, null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!isConnected()) {
            // not connected yet, so even onCreate was not called. Do not pass to CarActivity.
            mCarApi.disconnect();
            return;
        }
        handleCmd(CarActivity.CMD_ON_DESTROY, null);
        // disconnect last so that car api is valid in onDestroy call.
        mCarApi.disconnect();
    }

    private void handleCmd(int cmd, Object arg0) {
        if (isConnected()) {
            mCarActivity.dispatchCmd(cmd, arg0);
        } else {
            // not connected yet. queue it and return.
            Pair<Integer, Object> cmdToQ = new Pair<Integer, Object>(Integer.valueOf(cmd), arg0);
            synchronized (this) {
                mCmds.add(cmdToQ);
            }
        }
    }
}
