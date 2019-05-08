/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.RequiresPermission;
import android.car.CarBugreportManager.CarBugreportManagerCallback;
import android.car.ICarBugreportCallback;
import android.car.ICarBugreportService;
import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import libcore.io.IoUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;


/**
 * Bugreport service for cars. Should *only* be used on userdebug or eng builds.
 */
public class CarBugreportManagerService extends ICarBugreportService.Stub implements
        CarServiceBase {

    private static final String TAG = "CarBugreportMgrService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final Object mLock = new Object();

    private HandlerThread mHandlerThread = null;
    private Handler mHandler;
    private boolean mIsServiceRunning;

    // The socket at /dev/socket/dumpstate to communicate with dumpstate.
    private static final String DUMPSTATE_SOCKET = "dumpstate";
    private static final int SOCKET_CONNECTION_MAX_RETRY = 10;

    /**
     * Create a CarBugreportManagerService instance.
     *
     * @param context the context
     */
    public CarBugreportManagerService(Context context) {
        mContext = context;
    }

    @Override
    public void init() {
        // Initialize handler only if build is debuggable.
        if (Build.IS_DEBUGGABLE) {
            mHandlerThread = new HandlerThread(TAG);
            mHandlerThread.start();
            mHandler = new Handler(mHandlerThread.getLooper());
        }
    }

    @Override
    public void release() {
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
        }
    }

    @Override
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void requestBugreport(ParcelFileDescriptor pfd, ICarBugreportCallback callback) {
        if (mHandler == null) {
            // bugreport manager service is only available if the build is not a user build.
            reportError(callback, CarBugreportManagerCallback.CAR_BUGREPORT_SERVICE_NOT_AVAILABLE);
            return;
        }

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP,
                "requestBugreport");

        synchronized (mLock) {
            requestBugReportLocked(pfd, callback);
        }
    }

    @GuardedBy("mLock")
    private void requestBugReportLocked(ParcelFileDescriptor pfd, ICarBugreportCallback callback) {
        if (mIsServiceRunning) {
            Slog.w(TAG, "Bugreport Service already running");
            reportError(callback, CarBugreportManagerCallback.CAR_BUGREPORT_IN_PROGRESS);
            return;
        }
        mIsServiceRunning = true;
        mHandler.post(() -> dumpStateToFileWrapper(pfd, callback));
    }

    private void reportError(ICarBugreportCallback callback, int errorCode) {
        try {
            callback.onError(errorCode);
        } catch (RemoteException e) {
            Slog.e(TAG, "onError() failed: " + e.getMessage());
        }
    }

    @Override
    public void dump(PrintWriter writer) {
    }

    private void dumpStateToFileWrapper(ParcelFileDescriptor pfd, ICarBugreportCallback callback) {
        dumpStateToFile(pfd, callback);
        synchronized (mLock) {
            mIsServiceRunning = false;
        }
    }

    private void dumpStateToFile(ParcelFileDescriptor pfd, ICarBugreportCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "Dumpstate to file");
        }
        OutputStream out = null;
        InputStream in = null;
        LocalSocket localSocket;

        try {
            SystemProperties.set("ctl.start", "dumpstate");
        } catch (RuntimeException e) {
            Slog.e(TAG, "Failed to start dumpstate", e);
            reportError(callback, CarBugreportManagerCallback.CAR_BUGREPORT_DUMPSTATE_FAILED);
            return;
        }

        try {
            localSocket = connectToDumpstateService();
        } catch (IOException e) {
            Slog.e(TAG, "Timed out connecting to dumpstate socket", e);
            reportError(callback,
                    CarBugreportManagerCallback.CAR_BUGREPORT_DUMPSTATE_CONNECTION_FAILED);
            // Early out if connection to socket fails.
            return;
        }

        try {
            in = new DataInputStream(localSocket.getInputStream());
            out = new DataOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(pfd));
            rawCopyStream(out, in);
        } catch (IOException | RuntimeException e) {
            Slog.e(TAG, "Failed to grab dump state", e);
            reportError(callback, CarBugreportManagerCallback.CAR_BUGREPORT_DUMPSTATE_FAILED);
            return;
        } finally {
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(out);
        }

        try {
            callback.onFinished();
        } catch (RemoteException e) {
            Slog.e(TAG, "onFinished() failed: " + e.getMessage());
        }
    }

    private LocalSocket connectToDumpstateService() throws IOException {
        LocalSocket socket = new LocalSocket();
        // The dumpstate socket will be created by init upon receiving the
        // service request. It may not be ready by this point. So we will
        // keep retrying until success or reaching timeout.
        int retryCount = 0;
        while (true) {
            // First connection always fails so we just 1 second before trying to connect for the
            // first time too.
            SystemClock.sleep(/* ms= */ 1000);
            try {
                socket.connect(new LocalSocketAddress(DUMPSTATE_SOCKET,
                        LocalSocketAddress.Namespace.RESERVED));
                return socket;
            } catch (IOException e) {
                if (++retryCount >= SOCKET_CONNECTION_MAX_RETRY) {
                    throw e;
                }
                Log.i(TAG, "Failed to connect to dumpstate, will try again: " + e.getMessage());
            }
        }
    }

    // does not close the reader or writer.
    private static void rawCopyStream(OutputStream writer, InputStream reader) throws IOException {
        int read;
        byte[] buf = new byte[8192];
        while ((read = reader.read(buf, 0, buf.length)) > 0) {
            writer.write(buf, 0, read);
        }
    }
}
