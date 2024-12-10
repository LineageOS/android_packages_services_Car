/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.car.builtin.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.IBinder;
import android.os.IServiceCallback;
import android.os.RemoteException;
import android.os.ServiceManager;

/**
 * Helper class for {@code ServiceManager} API
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class ServiceManagerHelper {

    private ServiceManagerHelper()  {
        throw new UnsupportedOperationException();
    }

    /** Check {@link ServiceManager#getService(String)} */
    @Nullable
    public static IBinder getService(@NonNull String name) {
        return ServiceManager.getService(name);
    }

    /** Check {@link ServiceManager#checkService(String)} */
    @Nullable
    public static IBinder checkService(@NonNull String name) {
        return ServiceManager.checkService(name);
    }

    /** Check {@link ServiceManager#waitForDeclaredService(String)} */
    @Nullable
    public static IBinder waitForDeclaredService(@NonNull String name) {
        return ServiceManager.waitForDeclaredService(name);
    }

    /** Check {@link ServiceManager#addService(String, IBinder)} */
    public static void addService(@NonNull String name, @NonNull IBinder service) {
        ServiceManager.addService(name, service);
    }

    /** Check {@link ServiceManager#getDeclaredInstances(String)} */
    @Nullable
    public static String[] getDeclaredInstances(@NonNull String iface) {
        return ServiceManager.getDeclaredInstances(iface);
    }

    /**
     * The callback interface for {@link registerForNotifications}.
     */
    public interface IServiceRegistrationCallback {
        /**
         * Called when a service is registered.
         *
         * @param name the service name that has been registered with
         * @param binder the binder that is registered
         */
        void onRegistration(@NonNull String name, IBinder binder);
    }

    private static final class ServiceCallbackImpl extends IServiceCallback.Stub {
        private final IServiceRegistrationCallback mClientCallback;

        ServiceCallbackImpl(IServiceRegistrationCallback clientCallback) {
            mClientCallback = clientCallback;
        }

        @Override
        public void onRegistration(String name, IBinder binder) {
            mClientCallback.onRegistration(name, binder);
        }
    }

    /**
     * Register callback for service registration notifications.
     *
     * @throws RemoteException for underlying error.
     */
    public static void registerForNotifications(
            @NonNull String name, @NonNull IServiceRegistrationCallback callback)
            throws RemoteException {
        ServiceManager.registerForNotifications(name, new ServiceCallbackImpl(callback));
    }
}
