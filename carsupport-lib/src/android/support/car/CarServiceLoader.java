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

package android.support.car;

import android.content.Context;
import android.os.IBinder;

/**
 * CarServiceLoader is the abstraction for loading different types of car service.
 * @hide
 */
public abstract class CarServiceLoader {

    private final Context mContext;
    private final ServiceConnectionListener mListener;

    public CarServiceLoader(Context context, ServiceConnectionListener listener) {
        mContext = context;
        mListener = listener;
    }

    public abstract void connect() throws IllegalStateException;
    public abstract void disconnect();

    /**
     * Factory method to create non-standard Car*Manager for the given car service implementation.
     * This is necessary for Car*Manager which is relevant for one specific implementation like
     * projected.
     * @param serviceName service name for the given Car*Manager.
     * @param binder binder implementation received from car service
     * @return Car*Manager instance for the given serviceName / binder. null if given service is
     *         not supported.
     */
    public CarManagerBase createCustomCarManager(String serviceName, IBinder binder) {
        return null;
    }

    protected Context getContext() {
        return mContext;
    }

    protected ServiceConnectionListener getConnectionListener() {
        return mListener;
    }
}
