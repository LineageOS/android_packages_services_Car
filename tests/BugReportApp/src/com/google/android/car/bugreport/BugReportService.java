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
package com.google.android.car.bugreport;

import static com.google.android.car.bugreport.PackageUtils.getPackageVersion;

import android.annotation.Nullable;
import android.annotation.StringRes;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.car.Car;
import android.car.CarBugreportManager;
import android.car.CarNotConnectedException;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import libcore.io.IoUtils;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Service that captures screenshot and bug report using dumpstate and bluetooth snoop logs.
 *
 * <p>After collecting all the logs it updates the {@link MetaBugReport} using {@link
 * BugStorageProvider}, which in turn schedules bug report to upload.
 */
public class BugReportService extends Service {
    private static final String TAG = BugReportService.class.getSimpleName();

    /**
     * Extra data from intent - current bug report.
     */
    static final String EXTRA_META_BUG_REPORT = "meta_bug_report";

    // Wait a short time before starting to capture the bugreport and the screen, so that
    // bugreport activity can detach from the view tree.
    // It is ugly to have a timeout, but it is ok here because such a delay should not really
    // cause bugreport to be tainted with so many other events. If in the future we want to change
    // this, the best option is probably to wait for onDetach events from view tree.
    private static final int ACTIVITY_FINISH_DELAY = 1000; //in milliseconds

    private static final String BT_SNOOP_LOG_LOCATION = "/data/misc/bluetooth/logs/btsnoop_hci.log";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String NOTIFICATION_STATUS_CHANNEL_ID = "BUGREPORT_STATUS_CHANNEL_ID";
    private static final int BUGREPORT_IN_PROGRESS_NOTIF_ID = 1;

    // http://cs/android/frameworks/base/core/java/android/app/ActivityView.java
    private static final String ACTIVITY_VIEW_VIRTUAL_DISPLAY = "ActivityViewVirtualDisplay";
    private static final String OUTPUT_ZIP_FILE = "output_file.zip";
    private static final String PROGRESS_FILE = "progress.txt";

    private static final String MESSAGE_FAILURE_DUMPSTATE = "Failed to grab dumpstate";
    private static final String MESSAGE_FAILURE_ZIP = "Failed to zip files";

    // Binder given to clients
    private final IBinder mBinder = new ServiceBinder();

    private MetaBugReport mMetaBugReport;
    private NotificationManager mNotificationManager;
    private NotificationChannel mNotificationChannel;
    private AtomicBoolean mIsCollectingBugReport = new AtomicBoolean(false);
    private Handler mHandler;
    private ScheduledExecutorService mSingleThreadExecutor;
    private Car mCar;
    private CarBugreportManager mBugreportManager;
    private CarBugreportManager.CarBugreportManagerCallback mCallback;

    /**
     * Client binder.
     */
    public class ServiceBinder extends Binder {
        BugReportService getService() {
            // Return this instance of LocalService so clients can call public methods
            return BugReportService.this;
        }
    }

    @Override
    public void onCreate() {
        mNotificationManager = getSystemService(NotificationManager.class);
        mNotificationChannel = new NotificationChannel(
                NOTIFICATION_STATUS_CHANNEL_ID,
                getString(R.string.notification_bugreport_channel_name),
                NotificationManager.IMPORTANCE_MIN);
        mNotificationManager.createNotificationChannel(mNotificationChannel);
        mHandler = new Handler();
        mSingleThreadExecutor = Executors.newSingleThreadScheduledExecutor();
        mCar = Car.createCar(this);
        try {
            mBugreportManager = (CarBugreportManager) mCar.getCarManager(Car.CAR_BUGREPORT_SERVICE);
        } catch (CarNotConnectedException | NoClassDefFoundError e) {
            Log.w(TAG, "Couldn't get CarBugreportManager", e);
        }
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        if (mIsCollectingBugReport.get()) {
            Log.w(TAG, "bug report is already being collected, ignoring");
            Toast.makeText(this, R.string.toast_bug_report_in_progress, Toast.LENGTH_SHORT).show();
            return START_NOT_STICKY;
        }
        Log.i(TAG, String.format("Will start collecting bug report, version=%s",
                getPackageVersion(this)));
        mIsCollectingBugReport.set(true);

        Notification notification =
                new Notification.Builder(this, NOTIFICATION_STATUS_CHANNEL_ID)
                        .setContentTitle(getText(R.string.notification_bugreport_started))
                        .setSmallIcon(R.drawable.download_animation)
                        .build();
        startForeground(BUGREPORT_IN_PROGRESS_NOTIF_ID, notification);

        Bundle extras = intent.getExtras();
        mMetaBugReport = extras.getParcelable(EXTRA_META_BUG_REPORT);

        collectBugReport();

        // If the service process gets killed due to heavy memory pressure, do not restart.
        return START_NOT_STICKY;
    }

    public boolean isCollectingBugReport() {
        return mIsCollectingBugReport.get();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private void sendStatusInformation(@StringRes int resId) {
        // run on ui thread.
        mHandler.post(() -> Toast.makeText(this, getText(resId), Toast.LENGTH_LONG).show());
    }

    private void collectBugReport() {
        // Order is important when capturing. Screenshot should be first
        mSingleThreadExecutor.schedule(
                this::takeAllScreenshots, ACTIVITY_FINISH_DELAY, TimeUnit.MILLISECONDS);
        mSingleThreadExecutor.schedule(
                this::grabBtSnoopLog, ACTIVITY_FINISH_DELAY, TimeUnit.MILLISECONDS);
        mSingleThreadExecutor.schedule(
                this::dumpStateToFile, ACTIVITY_FINISH_DELAY, TimeUnit.MILLISECONDS);
    }

    private void takeAllScreenshots() {
        for (int displayId : getAvailableDisplayIds()) {
            takeScreenshot(displayId);
        }
    }

    @Nullable
    private File takeScreenshot(int displayId) {
        Log.i(TAG, String.format("takeScreenshot displayId=%d", displayId));
        File result = FileUtils.getFileWithSuffix(this, mMetaBugReport.getTimestamp(),
                "-" + displayId + "-screenshot.png");
        try {
            if (DEBUG) {
                Log.d(TAG, "Screen output: " + result.getName());
            }

            java.lang.Process process = Runtime.getRuntime()
                    .exec("/system/bin/screencap -d " + displayId + " -p "
                            + result.getAbsolutePath());

            // Waits for the command to finish.
            int err = process.waitFor();
            if (DEBUG) {
                Log.d(TAG, "screencap process finished: " + err);
            }
            return result;
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "screencap process failed: ", e);
            sendStatusInformation(R.string.toast_status_screencap_failed);
        }
        return null;
    }

    private List<Integer> getAvailableDisplayIds() {
        DisplayManager displayManager = getSystemService(DisplayManager.class);
        ArrayList<Integer> displayIds = new ArrayList<>();
        for (Display d : displayManager.getDisplays()) {
            Log.v(TAG,
                    "getAvailableDisplayIds: d.Name=" + d.getName() + ", d.id=" + d.getDisplayId());
            // We skip virtual displays as they are not captured by screencap.
            if (d.getName().contains(ACTIVITY_VIEW_VIRTUAL_DISPLAY)) {
                continue;
            }
            displayIds.add(d.getDisplayId());
        }
        return displayIds;
    }

    private void grabBtSnoopLog() {
        Log.i(TAG, "Grabbing bt snoop log");
        File result = FileUtils.getFileWithSuffix(this, mMetaBugReport.getTimestamp(),
                "-btsnoop.bin.log");
        try {
            copyBinaryStream(new FileInputStream(new File(BT_SNOOP_LOG_LOCATION)),
                    new FileOutputStream(result));
        } catch (IOException e) {
            // this regularly happens when snooplog is not enabled so do not log as an error
            Log.i(TAG, "Failed to grab bt snooplog, continuing to take bug report.", e);
        }
    }

    private void dumpStateToFile() {
        Log.i(TAG, "Dumpstate to file");
        File outputFile = FileUtils.getFile(this, mMetaBugReport.getTimestamp(), OUTPUT_ZIP_FILE);
        File progressFile = FileUtils.getFile(this, mMetaBugReport.getTimestamp(), PROGRESS_FILE);

        ParcelFileDescriptor outFd = null;
        ParcelFileDescriptor progressFd = null;
        try {
            outFd = ParcelFileDescriptor.open(outputFile,
                    ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE);

            progressFd = ParcelFileDescriptor.open(progressFile,
                    ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE);

            requestBugReport(outFd, progressFd);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to grab dump state", e);
            BugStorageUtils.setBugReportStatus(this, mMetaBugReport, Status.STATUS_WRITE_FAILED,
                    MESSAGE_FAILURE_DUMPSTATE);
            sendStatusInformation(R.string.toast_status_dump_state_failed);
        } finally {
            IoUtils.closeQuietly(outFd);
            IoUtils.closeQuietly(progressFd);
        }
    }

    // In Android Q and above, use the CarBugreportManager API
    private void requestBugReport(ParcelFileDescriptor outFd, ParcelFileDescriptor progressFd) {
        if (DEBUG) {
            Log.d(TAG, "Requesting a bug report from CarBugReportManager.");
        }
        mCallback = new CarBugreportManager.CarBugreportManagerCallback() {
            @Override
            public void onError(int errorCode) {
                Log.e(TAG, "Bugreport failed " + errorCode);
                sendStatusInformation(R.string.toast_status_failed);
                // TODO(b/133520419): show this error on Info page or add to zip file.
                scheduleZipTask();
            }

            @Override
            public void onFinished() {
                Log.i(TAG, "Bugreport finished");
                scheduleZipTask();
            }
        };
        mBugreportManager.requestZippedBugreport(outFd, progressFd, mCallback);
    }

    private void scheduleZipTask() {
        mSingleThreadExecutor.submit(this::zipDirectoryAndScheduleForUpload);
    }

    private void zipDirectoryAndScheduleForUpload() {
        try {
            // When OutputStream from openBugReportFile is closed, BugStorageProvider automatically
            // schedules an upload job.
            zipDirectoryToOutputStream(
                    FileUtils.createTempDir(this, mMetaBugReport.getTimestamp()),
                    BugStorageUtils.openBugReportFile(this, mMetaBugReport));
        } catch (IOException e) {
            Log.e(TAG, "Failed to zip files", e);
            BugStorageUtils.setBugReportStatus(this, mMetaBugReport, Status.STATUS_WRITE_FAILED,
                    MESSAGE_FAILURE_ZIP);
            sendStatusInformation(R.string.toast_status_failed);
        }
        mIsCollectingBugReport.set(false);
        sendStatusInformation(R.string.toast_status_finished);
    }

    @Override
    public void onDestroy() {
        if (DEBUG) {
            Log.d(TAG, "Service destroyed");
        }
    }

    private static void copyBinaryStream(InputStream in, OutputStream out) throws IOException {
        OutputStream writer = null;
        InputStream reader = null;
        try {
            writer = new DataOutputStream(out);
            reader = new DataInputStream(in);
            rawCopyStream(writer, reader);
        } finally {
            IoUtils.closeQuietly(reader);
            IoUtils.closeQuietly(writer);
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

    /**
     * Compresses a directory into a zip file. The method is not recursive. Any sub-directory
     * contained in the main directory and any files contained in the sub-directories will be
     * skipped.
     *
     * @param dirToZip  The path of the directory to zip
     * @param outStream The output stream to write the zip file to
     * @throws IOException if the directory does not exist, its files cannot be read, or the output
     *                     zip file cannot be written.
     */
    private void zipDirectoryToOutputStream(File dirToZip, OutputStream outStream)
            throws IOException {
        if (!dirToZip.isDirectory()) {
            throw new IOException("zip directory does not exist");
        }
        Log.v(TAG, "zipping directory " + dirToZip.getAbsolutePath());

        File[] listFiles = dirToZip.listFiles();
        ZipOutputStream zipStream = new ZipOutputStream(new BufferedOutputStream(outStream));
        try {
            for (File file : listFiles) {
                if (file.isDirectory()) {
                    continue;
                }
                String filename = file.getName();
                if (filename.equals(PROGRESS_FILE)) {
                    // Progress file is already part of zipped bugreport - skip it.
                    continue;
                }
                // only for the OUTPUT_FILE, we add invidiual entries to zip file
                if (filename.equals(OUTPUT_ZIP_FILE)) {
                    extractZippedFileToOutputStream(file, zipStream);
                } else {
                    FileInputStream reader = new FileInputStream(file);
                    addFileToOutputStream(filename, reader, zipStream);
                }
            }
        } finally {
            zipStream.close();
            outStream.close();
        }
        // Zipping successful, now cleanup the temp dir.
        FileUtils.deleteDirectory(dirToZip);
    }

    private void extractZippedFileToOutputStream(File file, ZipOutputStream zipStream)
            throws IOException {
        ZipFile zipFile = new ZipFile(file);
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            InputStream stream = zipFile.getInputStream(entry);
            addFileToOutputStream(entry.getName(), stream, zipStream);
        }
    }

    private void addFileToOutputStream(String filename, InputStream reader,
            ZipOutputStream zipStream) throws IOException {
        ZipEntry entry = new ZipEntry(filename);
        zipStream.putNextEntry(entry);
        rawCopyStream(zipStream, reader);
        zipStream.closeEntry();
        reader.close();
    }
}
