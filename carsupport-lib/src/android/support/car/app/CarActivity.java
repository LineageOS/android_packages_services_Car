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

import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.annotation.LayoutRes;
import android.support.car.Car;
import android.support.car.input.CarInputManager;
import android.view.LayoutInflater;
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
    private static final String TAG = "CarActivity";

    /**
     * Interface to connect {@link CarActivity} to {@link android.app.Activity} or other app model.
     * This interface provides utility for {@link CarActivity} to do things like manipulating view,
     * handling menu, and etc.
     */
    public interface Proxy {
        void setContentView(View view);
        void setContentView(int layoutResID);
        Resources getResources();
        View findViewById(int id);
        LayoutInflater getLayoutInflater();
        Intent getIntent();
        void finish();
        void setContentFragment(Fragment fragment, int containerId);
        CarInputManager getCarInputManager();
    }

    /** @hide */
    public static final int CMD_ON_CREATE = 0;
    /** @hide */
    public static final int CMD_ON_START = 1;
    /** @hide */
    public static final int CMD_ON_RESTART = 2;
    /** @hide */
    public static final int CMD_ON_RESUME = 3;
    /** @hide */
    public static final int CMD_ON_PAUSE = 4;
    /** @hide */
    public static final int CMD_ON_STOP = 5;
    /** @hide */
    public static final int CMD_ON_DESTROY = 6;
    /** @hide */
    public static final int CMD_ON_BACK_PRESSED = 7;
    /** @hide */
    public static final int CMD_ON_SAVE_INSTANCE_STATE = 8;
    /** @hide */
    public static final int CMD_ON_RESTORE_INSTANCE_STATE = 9;

    private final Proxy mProxy;
    private final Context mContext;

    public CarActivity(Proxy proxy, Context context) {
        mProxy = proxy;
        mContext = context;
    }

    public Context getContext() {
        return mContext;
    }

    public Resources getResources() {
        return mProxy.getResources();
    }

    public void setContentView(View view) {
        mProxy.setContentView(view);
    }

    public void setContentView(@LayoutRes int resourceId) {
        mProxy.setContentView(resourceId);
    }

    public LayoutInflater getLayoutInflater() {
        return mProxy.getLayoutInflater();
    }

    public Intent getIntent() {
        return mProxy.getIntent();

    public CarInputManager getInputManager() {
        return mProxy.getCarInputManager();
    }

    public void setContentFragment(Fragment fragment, int containerId) {
        mProxy.setContentFragment(fragment, containerId);
    }

    public View findViewById(int id) {
        return mProxy.findViewById(id);
    }

    public void finish() {
        mProxy.finish();
    }

    /** @hide */
    public void dispatchCmd(int cmd, Object arg0) {

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
            case CMD_ON_BACK_PRESSED:
                onBackPressed();
                break;
            case CMD_ON_SAVE_INSTANCE_STATE:
                onSaveInstanceState((Bundle) arg0);
                break;
            case CMD_ON_RESTORE_INSTANCE_STATE:
                onRestoreInstanceState((Bundle) arg0);
                break;
            default:
                throw new RuntimeException("Unknown dispatch cmd for CarActivity, " + cmd);
        }

    }

    protected void onCreate(Bundle savedInstanceState) {
    }

    protected void onStart() {
    }

    protected void onRestart() {
    }

    protected void onResume() {
    }

    protected void onPause() {
    }

    protected void onStop() {
    }

    protected void onDestroy() {
    }

    protected void onRestoreInstanceState(Bundle savedInstanceState) {
    }

    protected void onSaveInstanceState(Bundle outState) {
    }

    protected void onBackPressed() {
    }
}
