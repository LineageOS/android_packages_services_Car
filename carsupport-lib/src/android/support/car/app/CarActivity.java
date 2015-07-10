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

import android.content.Context;
import android.os.Bundle;
import android.support.car.Car;
import android.support.car.CarLibLog;
import android.support.car.CarNotConnectedException;
import android.util.Log;
import android.view.View;

/**
 * Abstraction for car UI. Car applications should implement this for car UI.
 * The API looks like {@link android.app.Activity}, and behaves like one, but it is neither
 * android {@link android.app.Activity} nor {@link android.app.Context}.
 * Applications should use {@link #getContext()} to get necessary {@link android.app.Context}.
 * To use car API, {@link #getCarApi()} can be used. The {@link Car} api passed is already
 * connected, and client does not need to call {@link Car#connect()}.
 */
public abstract class CarActivity {

    /**
     * Interface to connect {@link CarActivity} to {@link android.app.Activity} or other app model.
     * This interface provides utility for {@link CarActivity} to do things like manipulating view,
     * handling menu, and etc.
     */
    public interface Proxy {
        void setContentView(View view);
        void setContentView(int layoutResID);
        View findViewById(int id);
        void finish();
    }

    /** @hide */
    static final int CMD_ON_CREATE = 0;
    /** @hide */
    static final int CMD_ON_START = 1;
    /** @hide */
    static final int CMD_ON_RESTART = 2;
    /** @hide */
    static final int CMD_ON_RESUME = 3;
    /** @hide */
    static final int CMD_ON_PAUSE = 4;
    /** @hide */
    static final int CMD_ON_STOP = 5;
    /** @hide */
    static final int CMD_ON_DESTROY = 6;

    private final Proxy mProxy;
    private final Context mContext;
    private final Car mCarApi;

    public CarActivity(Proxy proxy, Context context, Car carApi) {
        mProxy = proxy;
        mContext = context;
        mCarApi = carApi;
    }

    public Context getContext() {
        return mContext;
    }

    public Car getCarApi() {
        return mCarApi;
    }

    /**
     * Check {@link android.app.Activity#setContentView(View)}.
     * @param view
     */
    public void setContentView(View view) {
        mProxy.setContentView(view);
    }

    public void setContentView(int layoutResID) {
        mProxy.setContentView(layoutResID);
    }

    public View findViewById(int id) {
        return mProxy.findViewById(id);
    }

    public void finish() {
        mProxy.finish();
    }

    /** @hide */
    void dispatchCmd(int cmd, Object arg0) {
        try {
            switch (cmd) {
                case CMD_ON_CREATE:
                    onCreate((Bundle) arg0);
                    break;
                case CMD_ON_START:
                    onStart();
                    break;
                case CMD_ON_RESTART:
                    onRestart();
                    break;
                case CMD_ON_RESUME:
                    onResume();
                    break;
                case CMD_ON_PAUSE:
                    onPause();
                    break;
                case CMD_ON_STOP:
                    onStop();
                    break;
                case CMD_ON_DESTROY:
                    onDestroy();
                    break;
                default:
                    throw new RuntimeException("Unknown dispatch cmd for CarActivity, " + cmd);
            }
        } catch (CarNotConnectedException e) {
            Log.w(CarLibLog.TAG_CAR, "Finish CarActivity due to exception ", e);
            if (cmd != CMD_ON_DESTROY) {
                finish();
            }
        }
    }

    protected void onCreate(Bundle savedInstanceState)
            throws CarNotConnectedException {
    }

    protected void onStart()
            throws CarNotConnectedException {
    }

    protected void onRestart()
            throws CarNotConnectedException {
    }

    protected void onResume()
            throws CarNotConnectedException {
    }

    protected void onPause()
            throws CarNotConnectedException {
    }

    protected void onStop()
            throws CarNotConnectedException {
    }

    protected void onDestroy()
            throws CarNotConnectedException {
    }

}
