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

import static android.car.CarBugreportManager.CarBugreportManagerCallback.CAR_BUGREPORT_DUMPSTATE_CONNECTION_FAILED;
import static android.car.CarBugreportManager.CarBugreportManagerCallback.CAR_BUGREPORT_DUMPSTATE_FAILED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.car.CarBugreportManager.CarBugreportManagerCallback;
import android.car.ICarBugreportCallback;
import android.car.ICarBugreportService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;

/**
 * Bugreport service for cars. Should *only* be used on userdebug or eng builds.
 */
public class CarBugreportManagerService extends ICarBugreportService.Stub implements
        CarServiceBase {

    private static final String TAG = "CarBugreportMgrService";

    /**
     * {@code dumpstate} progress prefixes.
     *
     * <p>The protocol is described in {@code frameworks/native/cmds/bugreportz/readme.md}.
     */
    private static final String BEGIN_PREFIX = "BEGIN:";
    private static final String PROGRESS_PREFIX = "PROGRESS:";
    private static final String OK_PREFIX = "OK:";
    private static final String FAIL_PREFIX = "FAIL:";

    private static final String BUGREPORTD_SERVICE = "car-bugreportd";

    // The socket definitions must match the actual socket names defined in car_bugreportd service
    // definition.
    private static final String BUGREPORT_PROGRESS_SOCKET = "car_br_progress_socket";
    private static final String BUGREPORT_OUTPUT_SOCKET = "car_br_output_socket";

    private static final int SOCKET_CONNECTION_MAX_RETRY = 10;

    private final Context mContext;
    private final Object mLock = new Object();

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private boolean mIsServiceRunning;

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
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    @Override
    public void release() {
        mHandlerThread.quitSafely();
    }

    @Override
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void requestZippedBugreport(ParcelFileDescriptor data, ICarBugreportCallback callback) {

        // Check the caller has proper permission
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP,
                "requestZippedBugreport");
        // Check the caller is signed with platform keys
        PackageManager pm = mContext.getPackageManager();
        int callingUid = Binder.getCallingUid();
        if (pm.checkSignatures(Process.myUid(), callingUid) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Caller " + pm.getNameForUid(callingUid)
                            + " does not have the right signature");
        }
        // Check the caller is the default designated bugreport app
        String defaultAppPkgName = mContext.getString(R.string.default_car_bugreport_application);
        String[] packageNamesForCallerUid = pm.getPackagesForUid(callingUid);
        boolean found = false;
        if (packageNamesForCallerUid != null) {
            for (String packageName : packageNamesForCallerUid) {
                if (defaultAppPkgName.equals(packageName)) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            throw new SecurityException("Caller " +  pm.getNameForUid(callingUid)
                    + " is not a designated bugreport app");
        }

        synchronized (mLock) {
            requestZippedBugReportLocked(data, callback);
        }
    }

    @GuardedBy("mLock")
    private void requestZippedBugReportLocked(
            ParcelFileDescriptor data, ICarBugreportCallback callback) {
        if (mIsServiceRunning) {
            Slog.w(TAG, "Bugreport Service already running");
            reportError(callback, CarBugreportManagerCallback.CAR_BUGREPORT_IN_PROGRESS);
            return;
        }
        mIsServiceRunning = true;
        mHandler.post(() -> startBugreportd(data, callback));
    }

    private void startBugreportd(ParcelFileDescriptor data, ICarBugreportCallback callback) {
        Slog.i(TAG, "Starting " + BUGREPORTD_SERVICE);
        try {
            SystemProperties.set("ctl.start", BUGREPORTD_SERVICE);
        } catch (RuntimeException e) {
            Slog.e(TAG, "Failed to start " + BUGREPORTD_SERVICE, e);
            reportError(callback, CAR_BUGREPORT_DUMPSTATE_FAILED);
            return;
        }
        processBugreportSockets(data, callback);
        synchronized (mLock) {
            mIsServiceRunning = false;
        }
    }

    private void handleProgress(String line, ICarBugreportCallback callback) {
        String progressOverTotal = line.substring(PROGRESS_PREFIX.length());
        String[] parts = progressOverTotal.split("/");
        if (parts.length != 2) {
            Slog.w(TAG, "Invalid progress line from bugreportz: " + line);
            return;
        }
        float progress;
        float total;
        try {
            progress = Float.parseFloat(parts[0]);
            total = Float.parseFloat(parts[1]);
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Invalid progress value: " + line, e);
            return;
        }
        if (total == 0) {
            Slog.w(TAG, "Invalid progress total value: " + line);
            return;
        }
        try {
            callback.onProgress(100f * progress / total);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to call onProgress callback", e);
        }
    }

    private void handleFinished(ParcelFileDescriptor output, ICarBugreportCallback callback) {
        Slog.i(TAG, "Finished reading bugreport");
        if (!copySocketToPfd(output, callback)) {
            return;
        }
        try {
            callback.onFinished();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to call onFinished callback", e);
        }
    }

    /**
     * Reads from dumpstate progress and output sockets and invokes appropriate callbacks.
     *
     * <p>dumpstate prints {@code BEGIN:} right away, then prints {@code PROGRESS:} as it
     * progresses. When it finishes or fails it prints {@code OK:pathToTheZipFile} or
     * {@code FAIL:message} accordingly.
     */
    private void processBugreportSockets(
            ParcelFileDescriptor output, ICarBugreportCallback callback) {
        LocalSocket localSocket = connectSocket(BUGREPORT_PROGRESS_SOCKET);
        if (localSocket == null) {
            reportError(callback, CAR_BUGREPORT_DUMPSTATE_CONNECTION_FAILED);
            return;
        }

        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(localSocket.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(PROGRESS_PREFIX)) {
                    handleProgress(line, callback);
                } else if (line.startsWith(FAIL_PREFIX)) {
                    String errorMessage = line.substring(FAIL_PREFIX.length());
                    Slog.e(TAG, "Failed to dumpstate: " + errorMessage);
                    reportError(callback, CAR_BUGREPORT_DUMPSTATE_FAILED);
                    return;
                } else if (line.startsWith(OK_PREFIX)) {
                    handleFinished(output, callback);
                    return;
                } else if (!line.startsWith(BEGIN_PREFIX)) {
                    Slog.w(TAG, "Received unknown progress line from dumpstate: " + line);
                }
            }
            Slog.e(TAG, "dumpstate progress unexpectedly ended");
            reportError(callback, CAR_BUGREPORT_DUMPSTATE_FAILED);
        } catch (IOException | RuntimeException e) {
            Slog.i(TAG, "Failed to read from progress socket", e);
            reportError(callback, CAR_BUGREPORT_DUMPSTATE_CONNECTION_FAILED);
        }
    }

    private boolean copySocketToPfd(
            ParcelFileDescriptor pfd, ICarBugreportCallback callback) {
        LocalSocket localSocket = connectSocket(BUGREPORT_OUTPUT_SOCKET);
        if (localSocket == null) {
            reportError(callback, CAR_BUGREPORT_DUMPSTATE_CONNECTION_FAILED);
            return false;
        }

        try (
            DataInputStream in = new DataInputStream(localSocket.getInputStream());
            DataOutputStream out =
                    new DataOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(pfd))
        ) {
            rawCopyStream(out, in);
        } catch (IOException | RuntimeException e) {
            Slog.e(TAG, "Failed to grab dump state from " + BUGREPORT_OUTPUT_SOCKET, e);
            reportError(callback, CAR_BUGREPORT_DUMPSTATE_FAILED);
            return false;
        }
        return true;
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
        // TODO(sgurun) implement
    }

    @Nullable
    private LocalSocket connectSocket(@NonNull String socketName) {
        LocalSocket socket = new LocalSocket();
        // The dumpstate socket will be created by init upon receiving the
        // service request. It may not be ready by this point. So we will
        // keep retrying until success or reaching timeout.
        int retryCount = 0;
        while (true) {
            // First connection always fails, so we wait 1 second before trying to connect.
            SystemClock.sleep(/* ms= */ 1000);
            try {
                socket.connect(new LocalSocketAddress(socketName,
                        LocalSocketAddress.Namespace.RESERVED));
                return socket;
            } catch (IOException e) {
                if (++retryCount >= SOCKET_CONNECTION_MAX_RETRY) {
                    Slog.i(TAG, "Failed to connect to dumpstate socket " + socketName
                            + " after " + retryCount + " retries", e);
                    return null;
                }
                Log.i(TAG, "Failed to connect to" + socketName + ". Will try again "
                        + e.getMessage());
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
