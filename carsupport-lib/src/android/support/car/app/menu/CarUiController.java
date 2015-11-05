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

package android.support.car.app.menu;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A controller for a {@link android.support.car.app.CarActivity} to access its car UI.
 *
 * @hide
 */
public class CarUiController {
    private static final String TAG = "CarUiController";
    private static final String SDK_CLASS_NAME = ".CarUiEntry";
    private static final String CAR_UI_PKG = "android.support.car.ui";

    private final CarDrawerActivity mActivity;
    // TODO: Add more UI control methods
    private Method mGetContentViewMethod;
    private Method mSetScrimColor;
    private Method mSetTitle;
    private Method mSetCarMenuBinder;
    private Method mGetFragmentContainerId;
    private Method mSetLightMode;
    private Method mSetAutoLightDarkMode;
    private Method mSetDarkMode;
    private Method mSetBackground;
    private Method mSetBackgroundResource;
    private Method mOnSaveInstanceState;
    private Method mOnRestoreInstanceState;
    private Method mCloseDrawer;
    private Method mOpenDrawer;
    private Method mShowMenu;

    private Object mCarUiEntryClass;

    public CarUiController(CarDrawerActivity activity) {
        mActivity = activity;
        init();
    }

    public void init() {
        try{
            // TODO: need to verify the certificate of the car ui lib apk.
            Context carUiContext = mActivity.getContext().createPackageContext(
                    CAR_UI_PKG,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader classLoader = carUiContext.getClassLoader();
            Class<?> loadedClass = classLoader.loadClass(CAR_UI_PKG + SDK_CLASS_NAME);
            for (Method m : loadedClass.getDeclaredMethods()) {
                switch(m.getName()) {
                    case "getContentView":
                        mGetContentViewMethod = m;
                        break;
                    case "setScrimColor":
                        mSetScrimColor = m;
                        break;
                    case "setTitle":
                        mSetTitle = m;
                        break;
                    case "setCarMenuBinder":
                        mSetCarMenuBinder = m;
                        break;
                    case "getFragmentContainerId":
                        mGetFragmentContainerId = m;
                        break;
                    case "setLightMode":
                        mSetLightMode = m;
                        break;
                    case "setDarkMode":
                        mSetDarkMode = m;
                        break;
                    case "setAutoLightDarkMode":
                        mSetAutoLightDarkMode = m;
                        break;
                    case "setBackground":
                        mSetBackground = m;
                        break;
                    case "setBackgroundResource":
                        mSetBackgroundResource = m;
                        break;
                    case "onSaveInstanceState":
                        mOnSaveInstanceState = m;
                        break;
                    case "onRestoreInstanceState":
                        mOnRestoreInstanceState = m;
                        break;
                    case "closeDrawer":
                        mCloseDrawer = m;
                        break;
                    case "openDrawer":
                        mOpenDrawer = m;
                        break;
                    case "showMenu":
                        mShowMenu = m;
                        break;
                }
            }
            mCarUiEntryClass = loadedClass.getConstructors()[0].newInstance(
                    carUiContext, mActivity.getContext());
        } catch (PackageManager.NameNotFoundException | ClassNotFoundException
                | InvocationTargetException | InstantiationException
                | IllegalAccessException e) {
            Log.e(TAG, "Unable to load Car ui entry class.", e);
        }
    }

    public int getFragmentContainerId() {
        return (int) invoke(mGetFragmentContainerId);
    }

    public void setTitle(CharSequence title) {
        invoke(mSetTitle, title);
    }

    public void setScrimColor(int color) {
        invoke(mSetScrimColor, color);
    }

    public View getContentView() {
        return (View) invoke(mGetContentViewMethod);
    }

    private Object invoke(Method m, Object... params) {
        try {
            return m.invoke(mCarUiEntryClass, params);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            Log.e(TAG, "Error invoking: " + m.getName(), e);
        }
        return null;
    }

    public void registerCarMenuCallbacks(IBinder callbacks) {
        invoke(mSetCarMenuBinder, callbacks);
    }

    /**
     * Set the System UI to be light.
     */
    public void setLightMode() {
        invoke(mSetLightMode);
    }

    /**
     * Set the System UI to be dark.
     */
    public void setDarkMode() {
        invoke(mSetDarkMode);
    }

    /**
     * Set the System UI to be dark during day mode and light during night mode.
     */
    public void setAutoLightDarkMode() {
        invoke(mSetAutoLightDarkMode);
    }

    /**
     * Sets the application background to the given {@link android.graphics.Bitmap}.
     *
     * @param bitmap to use as background.
     */
    public void setBackground(Bitmap bitmap) {
        invoke(mSetBackground, bitmap);
    }

    /**
     * Sets the background to a given resource.
     * The resource should refer to a Drawable object or 0 to remove the background.
     *
     * @param resId The identifier of the resource.
     */
    public void setBackgroundResource(int resId) {
        invoke(mSetBackgroundResource, resId);
    }

    public void onRestoreInstanceState(Bundle savedState) {
        invoke(mOnRestoreInstanceState, savedState);
    }

    public void onSaveInstanceState(Bundle outState) {
        invoke(mOnSaveInstanceState, outState);
    }

    public void closeDrawer() {
        invoke(mCloseDrawer);
    }

    public void openDrawer() {
        invoke(mOpenDrawer);
    }

    public void showMenu(String id, String title) {
        invoke(mShowMenu, id, title);
    }
}
