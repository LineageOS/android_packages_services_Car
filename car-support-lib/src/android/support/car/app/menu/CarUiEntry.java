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
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.View;

/**
 * A base class for a car ui entry which is used for loading and manipulating common car
 * app decor window (CarUi).
 *
 * A CarUi provider provides essential ui elements that a car app may want to use. The CarUi is
 * loaded by apps at runtime, similar to a shared library, but via reflection through a class that
 * extends {@link android.support.car.app.menu.CarUiEntry} from a separate apk
 * called CarUiProvider. Depending on the different platforms, the CarUiProvider may
 * be different and can be customized by different car makers. However, it is required that a
 * set of basic ui elements and functionalities exist in the CarUiProvider. This class defines
 * the set of must have functions in a CarUiProvider.
 */
public abstract class CarUiEntry {
    protected Context mAppContext;
    protected Context mUiLibContext;

    public CarUiEntry(Context uiLibContext, Context appContext) {
        mAppContext = appContext;
        mUiLibContext = uiLibContext.createConfigurationContext(
                appContext.getResources().getConfiguration());
    }

    abstract public View getContentView();

    abstract public void setCarMenuBinder(IBinder binder) throws RemoteException;

    abstract public int getFragmentContainerId();

    abstract public void setBackground(Bitmap bitmap);

    abstract public void setBackgroundResource(int resId);

    abstract public void hideMenuButton();

    abstract public void restoreMenuDrawable();

    abstract public void setMenuProgress(float progress);

    abstract public void setScrimColor(int color);

    abstract public void setTitle(CharSequence title);

    abstract public void setTitleText(CharSequence title);

    abstract public void closeDrawer();

    abstract public void openDrawer();

    abstract public void showMenu(String id, String title);

    abstract public void setMenuButtonColor(int color);

    abstract public void showTitle();

    abstract public void hideTitle();

    abstract public void setLightMode();

    abstract public void setDarkMode();

    abstract public void setAutoLightDarkMode();

    abstract public void onRestoreInstanceState(Bundle savedInstanceState);

    abstract public void onSaveInstanceState(Bundle outState);

    abstract public void onStart();

    abstract public void onResume();

    abstract public void onPause();

    abstract public void onStop();
}
