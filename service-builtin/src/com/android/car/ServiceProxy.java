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

package com.android.car;

import static com.android.car.UpdatablePackageDependency.PROXIED_SERVICE_DO_ATTACH_BASE_CONTEXT;
import static com.android.car.UpdatablePackageDependency.PROXIED_SERVICE_DO_DUMP;
import static com.android.car.UpdatablePackageDependency.PROXIED_SERVICE_SET_BUILTIN_PACKAGE_CONTEXT;

import android.annotation.Nullable;
import android.app.Service;
import android.car.builtin.util.Slogf;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Base class to wrap Service lifecycle with real Service code loaded from updatable car service
 * package. Public Service defined inside builtin should inherit this to provide automatic
 * wrapping.
 */
public class ServiceProxy extends Service {
    private static final String TAG = "CAR.ServiceProxy";

    private final String mRealServiceClassName;

    private UpdatablePackageContext mUpdatablePackageContext;
    private Class mRealServiceClass;
    private Service mRealService;

    public ServiceProxy(String realServiceClassName) {
        mRealServiceClassName = realServiceClassName;
    }

    @Override
    public void onCreate() {
        init();
        mRealService.onCreate();
    }

    @Override
    public void onDestroy() {
        mRealService.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mRealService.onBind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return mRealService.onUnbind(intent);
    }
    @Override
    public void onRebind(Intent intent) {
        mRealService.onRebind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return mRealService.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        executeAMethod(PROXIED_SERVICE_DO_DUMP,
                new Class[]{FileDescriptor.class, PrintWriter.class, args.getClass()},
                new Object[]{fd, writer, args}, true);
    }

    private void init() {
        mUpdatablePackageContext = UpdatablePackageContext.create(this);
        try {
            mRealServiceClass = mUpdatablePackageContext.getClassLoader().loadClass(
                    mRealServiceClassName);
            // Use default constructor always
            Constructor constructor = mRealServiceClass.getConstructor();
            mRealService = (Service) constructor.newInstance();
            executeAMethod(PROXIED_SERVICE_DO_ATTACH_BASE_CONTEXT, new Class[]{Context.class},
                    new Object[]{mUpdatablePackageContext}, false);
            executeAMethod(PROXIED_SERVICE_SET_BUILTIN_PACKAGE_CONTEXT, new Class[]{Context.class},
                    new Object[]{this}, false);
        } catch (Exception e) {
            throw new RuntimeException("Cannot load class:" + mRealServiceClassName, e);
        }
    }

    /** Reflecion helper */
    @Nullable
    public Object executeAMethod(String methodName, Class[] argClasses, Object[] args,
            boolean ignoreFailure) {
        try {
            Method m = mRealServiceClass.getMethod(methodName, argClasses);
            return m.invoke(mRealService, args);
        } catch (Exception e) {
            String msg = "cannot load method:" + methodName + " for:" + mRealServiceClassName;
            if (ignoreFailure) {
                Slogf.w(TAG, msg, e);
                return null;
            } else {
                throw new RuntimeException(msg, e);
            }
        }
    }

    /** Check {@link Service#attachBaseContext(Context)}. */
    public void  doAttachBaseContext(Context newBase) {
        attachBaseContext(newBase);
    }

    /** Check {@link Service#dump(FileDescriptor, PrintWriter, String[])}. */
    public void doDump(FileDescriptor fd, PrintWriter writer, String[] args) {
        dump(fd, writer, args);
    }
}
