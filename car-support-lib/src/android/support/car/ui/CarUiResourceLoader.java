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
package android.support.car.ui;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

public class CarUiResourceLoader {
    private static final String TAG = "CarUiResourceLoader";
    private static final String RELEASE_RESOURCE_NAME = "release";
    private static final String CAR_UI_PACKAGE = "android.support.car.ui";
    private static final int MAX_CACHE_SIZE = 5;

    private static ColorDrawable sTransparentDrawable;
    private static Map<Configuration, Resources> sConfigurationResourceMap = new HashMap<>();
    private static AssetManager sAssetManager;

    /**
     * Returns {@code true} if this is a release build.
     */
    public static boolean isRelease(Context context) {
        return getBoolean(context, RELEASE_RESOURCE_NAME, false);
    }

    /**
     * @return R.drawable.drawableName from car ui lib. If it doesn't exist in car ui lib,
     *         it will try to load it from the provided context's resources. If that fails,
     *         it will return a transparent drawable.
     */
    public static synchronized Drawable getDrawable(Context context, String drawableName) {
        Resources res = getResources(context);
        Log.d(TAG, "loading drawable " + drawableName);
        if (res == null) {
            Log.d(TAG, "loading drawable locally " + drawableName);
            return getDrawableLocally(context, drawableName);
        }

        int id = res.getIdentifier(drawableName, "drawable", CAR_UI_PACKAGE);
        if (id == 0) {
            return getDrawableLocally(context, drawableName);
        } else {
            Drawable drawable = res.getDrawableForDensity(id,
                    context.getResources().getDisplayMetrics().densityDpi,
                    null);
            Log.d(TAG, " returning drawable " + drawableName + " for "
                    + context.getResources().getDisplayMetrics().densityDpi + " null? " + (drawable == null));
            return drawable;
        }
    }

    private static Drawable getDrawableLocally(Context context, String drawableName) {
        Log.e(TAG, "Resource not found in car ui lib. Loading local version of: " + drawableName);
        int id = context.getResources()
                .getIdentifier(drawableName, "drawable", context.getPackageName());
        if (id == 0) {
            Log.e(TAG, "Resource not found in car ui lib or " + context.getPackageName() +
                    ": " + drawableName);
            return getTransparentDrawable();
        } else {
            return context.getDrawable(id);
        }
    }

    private static Drawable getTransparentDrawable() {
        // Return a transparent drawable rather than crashing.
        if (sTransparentDrawable == null) {
            sTransparentDrawable = new ColorDrawable(Color.TRANSPARENT);
        }
        return sTransparentDrawable;
    }

    /**
     * @return R.bool.booleanName from car ui lib. If it doesn't exist in car ui lib, it will return
     *         the def provided.
     */
    public static synchronized boolean getBoolean(
            Context context, String booleanName, boolean def) {
        Resources res = getResources(context);
        if (res == null) {
            return def;
        }

        int id = res.getIdentifier(booleanName, "bool", CAR_UI_PACKAGE);
        if (id == 0) {
            return def;
        } else {
            return res.getBoolean(id);
        }
    }

    /**
     * @return R.dimen.dimenName from car ui lib. If it doesn't exist in car ui lib, it will try
     *         to load it from the provided context's resources. If that fails, it will return 0.
     */
    public static synchronized int getDimensionPixelSize(Context context, String dimenName) {
        Resources res = getResources(context);
        if (res == null) {
            return getDimensionPixelSizeLocally(context, dimenName);
        }

        int id = res.getIdentifier(dimenName, "dimen", CAR_UI_PACKAGE);
        if (id == 0) {
            return getDimensionPixelSizeLocally(context, dimenName);
        } else {
            return res.getDimensionPixelSize(id);
        }
    }

    private static int getDimensionPixelSizeLocally(Context context, String dimenName) {
        Log.e(TAG, "Resource not found in car ui lib. Loading local version of: " + dimenName);
        int id = context.getResources().getIdentifier(dimenName, "dimen", context.getPackageName());
        if (id == 0) {
            Log.e(TAG, "Resource not found in car ui lib or " + context.getPackageName() +
                    ": " + dimenName);
            return 0;
        } else {
            return context.getResources().getDimensionPixelSize(id);
        }
    }

    /**
     * @return {@link android.content.res.Resources} for car ui lib with the display metrics
     *      and configuration of the provided context. If car ui lib isn't installed,
     *      return {@code null}.
     */
    private static Resources getResources(Context context) {
        Configuration configuration = context.getResources().getConfiguration();
        Resources res = sConfigurationResourceMap.get(configuration);
        if (res == null) {
            if (sAssetManager == null) {
                try {
                    sAssetManager = context.getPackageManager().getResourcesForApplication(
                            CAR_UI_PACKAGE).getAssets();
                } catch (NameNotFoundException e) {
                    return null;
                }
            }
            res = new Resources(sAssetManager,
                    context.getResources().getDisplayMetrics(),
                    context.getResources().getConfiguration());
            if (sConfigurationResourceMap.size() > MAX_CACHE_SIZE) {
                sConfigurationResourceMap.remove(
                        sConfigurationResourceMap.keySet().iterator().next());
            }
            sConfigurationResourceMap.put(configuration, res);
        }
        return res;
    }
}
