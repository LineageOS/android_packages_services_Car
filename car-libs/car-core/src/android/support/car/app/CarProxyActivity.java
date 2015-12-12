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
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.support.car.input.CarInputManager;
import android.support.car.input.CarRestrictedEditText;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;

import javax.annotation.concurrent.GuardedBy;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * android Activity controlling / proxying {@link CarActivity}. Applications should have its own
 * {@link android.app.Activity} overriding only constructor.
 */
public class CarProxyActivity extends Activity {
    private final Class mCarActivityClass;
    // no synchronization, but main thread only
    private CarActivity mCarActivity;
    // no synchronization, but main thread only
    private CarInputManager mInputManager;

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

        @Override
        public Intent getIntent() {
            return CarProxyActivity.this.getIntent();
        }

        @Override
        public CarInputManager getCarInputManager() {
            return CarProxyActivity.this.mInputManager;
        }

        @Override
        public void requestPermissions(String[] permissions, int requestCode) {
            CarProxyActivity.this.requestPermissions(permissions, requestCode);
        }

        @Override
        public boolean shouldShowRequestPermissionRationale(String permission) {
            return CarProxyActivity.this.shouldShowRequestPermissionRationale(permission);
        }

        @Override
        public void setIntent(Intent i) {
            CarProxyActivity.this.setIntent(i);
        }

        @Override
        public MenuInflater getMenuInflater() {
            return CarProxyActivity.this.getMenuInflater();
        }

        @Override
        public void finishAfterTransition() {
            CarProxyActivity.this.finishAfterTransition();
        }

        @Override
        public void startActivityForResult(Intent intent, int requestCode) {
            CarProxyActivity.this.startActivityForResult(intent, requestCode);
        }

        @Override
        public boolean isFinishing() {
            return CarProxyActivity.this.isFinishing();
        }

        @Override
        public Window getWindow() {
            return CarProxyActivity.this.getWindow();
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
            StringBuilder msg = new StringBuilder(
                    "Cannot construct given CarActivity, no constructor for ");
            msg.append(mCarActivityClass.getName());
            msg.append("\nAvailable constructors are [");
            final Constructor<?>[] others = mCarActivityClass.getConstructors();
            for (int i=0; i<others.length; i++ ) {
                msg.append("\n  ");
                msg.append(others[i].toString());
            }
            msg.append("\n]");
            throw new RuntimeException(msg.toString(), e);
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
        mInputManager = new EmbeddedInputManager(this);
    }

    @Override
    public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
        final View view = mCarActivity.onCreateView(parent, name, context, attrs);
        if (view != null) {
            return view;
        }
        return super.onCreateView(parent, name, context, attrs);
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
    public Object onRetainNonConfigurationInstance() {
        return mCarActivity.onRetainNonConfigurationInstance();
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

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mCarActivity.dispatchCmd(CarActivity.CMD_ON_CONFIG_CHANGED, newConfig);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] results) {
        mCarActivity.dispatchCmd(CarActivity.CMD_ON_REQUEST_PERMISSIONS_RESULT,
                new Integer(requestCode), permissions, convertArray(results));
    }

    @Override
    protected void onNewIntent(Intent i) {
        mCarActivity.dispatchCmd(CarActivity.CMD_ON_NEW_INTENT, i);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mCarActivity.dispatchCmd(CarActivity.CMD_ON_LOW_MEMORY);
    }

    private static final class EmbeddedInputManager extends CarInputManager {
        private static final String TAG = "EmbeddedInputManager";

        private final InputMethodManager mInputManager;
        private final WeakReference<CarProxyActivity> mActivity;

        public EmbeddedInputManager(CarProxyActivity activity) {
            mActivity = new WeakReference<CarProxyActivity>(activity);
            mInputManager = (InputMethodManager) mActivity.get()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
        }

        @Override
        public void startInput(CarRestrictedEditText view) {
            view.requestFocus();
            mInputManager.showSoftInput(view, 0);
        }

        @Override
        public void stopInput() {
            if (mActivity.get() == null) {
                return;
            }

            View view = mActivity.get().getCurrentFocus();
            if (view != null) {
                mInputManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
            } else {
                Log.e(TAG, "stopInput called, but no view is accepting input");
            }
        }

        @Override
        public boolean isValid() {
            return mActivity.get() != null;
        }

        @Override
        public boolean isInputActive() {
            return mInputManager.isActive();
        }

        @Override
        public boolean isCurrentCarEditable(CarRestrictedEditText view) {
            return mInputManager.isActive(view);
        }
    }

    private static Integer[] convertArray(int[] array) {
        Integer[] grantResults = new Integer[array.length];
        for (int i = 0; i < array.length; i++) {
            grantResults[i] = array[i];
        }
        return grantResults;
    }
}
