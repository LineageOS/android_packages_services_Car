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

import android.car.cluster.InstrumentClusterRenderer;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import dalvik.system.PathClassLoader;

import java.lang.reflect.Method;

/**
 * Responsible for loading {@link InstrumentClusterRenderer} from separate android.car.cluster APK
 * library.
 */
public class InstrumentClusterRendererLoader {
    private final static String TAG = CarLog.TAG_SERVICE;

    private final static String sCreateRendererMethod = "createRenderer";

    private static InstrumentClusterRenderer sRenderer;
    private static Object sync = new Object();

    /**
     * Load {@link InstrumentClusterRenderer} from separate APK and cache it. Subsequent calls to
     * this function will return cached reference to the renderer.
     */
    public static InstrumentClusterRenderer getRenderer(Context context) {
        synchronized (sync) {
            if (sRenderer == null) {
                Display display = getInstrumentClusterDisplay(context);
                if (display != null) {
                    createRenderer(context, display);
                } else {
                    Log.e(TAG, "Instrument cluster display not found.");
                }
            }
        }
        return sRenderer;
    }

    /**
     * Returns true if instrument cluster renderer installed and secondary display is available.
     */
    public static boolean isRendererAvailable(Context context) {
        PackageManager packageManager = context.getPackageManager();
        try {
            packageManager.getPackageInfo(getRendererPackageName(context), 0);
            return getInstrumentClusterDisplay(context) != null;
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    /** To prevent instantiation of a singleton class. */
    private InstrumentClusterRendererLoader() {}

    private static void createRenderer(Context context, Display display) {
        String packageName = getRendererPackageName(context);

        try {
            sRenderer = load(context, packageName, getRendererFactoryClassName(context));
        } catch (Exception e) {
            Log.e(TAG, "Failed to load renderer class: " + e.getMessage(), e);
            throw new RuntimeException(e);
        }

        sRenderer.onCreate(createPackageContext(context, packageName), display);
        sRenderer.onStart();
    }

    /**
     * Creates package context for given package name. It is necessary to get renderer's context
     * so appropriate resources will be loaded in the renderer code.
     */
    private static Context createPackageContext(Context currentContext, String packageName) {
        try {
            // TODO: check APK certificate.
            return currentContext.createPackageContext(packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Package not found: " + packageName, e);
            throw new IllegalStateException(e);
        }
    }

    private static Display getInstrumentClusterDisplay(Context context) {
        DisplayManager displayManager = context.getSystemService(DisplayManager.class);
        Display[] displays = displayManager.getDisplays();

        Log.d(TAG, "There are currently " + displays.length + " displays connected.");
        for (Display display : displays) {
            Log.d(TAG, "  " + display);
        }

        if (displays.length > 1) {
            // TODO: assuming that secondary display is instrument cluster. Put this into settings?
            return displays[1];
        }
        return null;
    }

    /** Returns instrument cluster renderer or null if renderer package is not found */
    private static InstrumentClusterRenderer load(Context context, String packageName,
            String factoryClassName) throws Exception {
        PackageManager packageManager = context.getPackageManager();

        assertSignature(context.getApplicationContext(), packageManager, packageName);

        String clusterRendererApk = packageManager.getApplicationInfo(packageName, 0).sourceDir;

        PathClassLoader pathClassLoader = new dalvik.system.PathClassLoader(
                clusterRendererApk, ClassLoader.getSystemClassLoader());

        Class<?> factoryClass = Class.forName(factoryClassName, true, pathClassLoader);

        Method createRendererMethod = factoryClass.getMethod(sCreateRendererMethod);

        Object rendererObject = createRendererMethod.invoke(null /* static */);

        if (rendererObject == null) {
            Log.e(TAG, factoryClassName + "#" + sCreateRendererMethod + " returned null.");
            throw new IllegalStateException();
        }

        if (!(rendererObject instanceof InstrumentClusterRenderer)) {
            Log.e(TAG, factoryClassName + "#" + sCreateRendererMethod + " returned unexpected"
                    + " object of class " + rendererObject.getClass().getCanonicalName());
            throw new IllegalStateException();
        }
        return (InstrumentClusterRenderer) rendererObject;
    }

    /** Asserts that signature of a given alienPackageName matches with the current application. */
    private static void assertSignature(Context applicationContext, PackageManager packageManager,
            String alienPackageName) {
        String carServicePackage = applicationContext.getPackageName();
        int signatureMatch = packageManager.checkSignatures(carServicePackage, alienPackageName);
        if (signatureMatch != PackageManager.SIGNATURE_MATCH) {
            throw new IllegalArgumentException(
                    "Signature doesn't match for package: " + alienPackageName
                            + ", signatureMatch: " + signatureMatch);
        }
    }

    private static String getRendererFactoryClassName(Context context) {
        return context.getString(R.string.instrumentClusterRendererFactoryClass);
    }

    private static String getRendererPackageName(Context context) {
        return context.getString(R.string.instrumentClusterRendererPackage);
    }
}
