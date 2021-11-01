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

package com.google.android.car.kitchensink.watchdog;

import android.annotation.IntDef;
import android.app.AlertDialog;
import android.car.watchdog.CarWatchdogManager;
import android.car.watchdog.IoOveruseStats;
import android.car.watchdog.ResourceOveruseStats;
import android.content.Context;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.internal.annotations.GuardedBy;

import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fragment to test the I/O monitoring of Car Watchdog.
 *
 * <p>Before running the tests, start a custom performance collection, this enables the watchdog
 * daemon to read proc stats more frequently and reduces the test wait time. Then run the dumpsys
 * command to reset I/O overuse counters in the adb shell, which clears any previous stats saved by
 * watchdog. After the test is finished, stop the custom performance collection, this resets
 * watchdog's I/O stat collection to the default interval.
 *
 * <p>Commands:
 *
 * <p>adb shell dumpsys android.automotive.watchdog.ICarWatchdog/default --start_perf \
 * --max_duration 600 --interval 1
 *
 * <p>adb shell dumpsys android.automotive.watchdog.ICarWatchdog/default \
 * --reset_resource_overuse_stats shared:com.google.android.car.uid.kitchensink
 *
 * <p>adb shell dumpsys android.automotive.watchdog.ICarWatchdog/default --stop_perf /dev/null
 */
public class CarWatchdogTestFragment extends Fragment {
    private static final long TEN_MEGABYTES = 1024 * 1024 * 10;
    private static final int DISK_DELAY_MS = 3000;
    private static final String TAG = "CarWatchdogTestFragment";
    private static final double WARN_THRESHOLD_PERCENT = 0.8;
    private static final double EXCEED_WARN_THRESHOLD_PERCENT = 0.9;

    private static final int NOTIFICATION_STATUS_NO = 0;
    private static final int NOTIFICATION_STATUS_INVALID = 1;
    private static final int NOTIFICATION_STATUS_VALID = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"NOTIFICATION_STATUS"}, value = {
            NOTIFICATION_STATUS_NO,
            NOTIFICATION_STATUS_INVALID,
            NOTIFICATION_STATUS_VALID
    })
    private @interface NotificationStatus{}

    private static final int NOTIFICATION_TYPE_WARNING = 0;
    private static final int NOTIFICATION_TYPE_OVERUSE = 1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"NOTIFICATION_TYPE"}, value = {
            NOTIFICATION_TYPE_WARNING,
            NOTIFICATION_TYPE_OVERUSE,
    })
    private @interface NotificationType{}

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private Context mContext;
    private CarWatchdogManager mCarWatchdogManager;
    private KitchenSinkActivity mActivity;
    private File mTestDir;
    private TextView mOveruseTextView;
    private TextViewSetter mTextViewSetter;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        mContext = getContext();
        mActivity = (KitchenSinkActivity) getActivity();
        mActivity.requestRefreshManager(
                () -> {
                    mCarWatchdogManager = mActivity.getCarWatchdogManager();
                },
                new Handler(mContext.getMainLooper()));
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.car_watchdog_test, container, false);

        mOveruseTextView = view.findViewById(R.id.io_overuse_textview);
        Button nonRecurringIoOveruseButton = view.findViewById(R.id.non_recurring_io_overuse_btn);
        Button recurringIoOveruseButton = view.findViewById(R.id.recurring_io_overuse_btn);

        try {
            mTestDir =
                    Files.createTempDirectory(mActivity.getFilesDir().toPath(), "testDir").toFile();
        } catch (IOException e) {
            e.printStackTrace();
            mActivity.finish();
        }

        nonRecurringIoOveruseButton.setOnClickListener(
                v -> mExecutor.execute(
                        () -> {
                            mTextViewSetter = new TextViewSetter(mOveruseTextView, mActivity);
                            mTextViewSetter.set("Starting non-recurring I/O overuse test.");
                            IoOveruseListener listener = addResourceOveruseListener();

                            if (overuseDiskIo(listener)) {
                                showAlert("Non-recurring I/O overuse test",
                                        "Test completed successfully.", 0);
                            } else {
                                mTextViewSetter.setPermanent(
                                        "Non-recurring I/O overuse test failed.");
                            }

                            finishTest(listener);
                            Log.d(TAG, "Non-recurring I/O overuse test completed.");
                        }));

        recurringIoOveruseButton.setOnClickListener(
                v -> mExecutor.execute(
                        () -> {
                            mTextViewSetter = new TextViewSetter(mOveruseTextView, mActivity);
                            mTextViewSetter.set("Starting recurring I/O overuse test.");
                            IoOveruseListener listener = addResourceOveruseListener();

                            if (!overuseDiskIo(listener)) {
                                mTextViewSetter.setPermanent("First disk I/O overuse failed.");
                                finishTest(listener);
                                return;
                            }
                            mTextViewSetter.setPermanent(
                                    "First disk I/O overuse completed successfully."
                                            + System.lineSeparator());

                            if (!overuseDiskIo(listener)) {
                                mTextViewSetter.setPermanent("Second disk I/O overuse failed.");
                                finishTest(listener);
                                return;
                            }
                            mTextViewSetter.setPermanent(
                                    "Second disk I/O overuse completed successfully."
                                            + System.lineSeparator());

                            if (!overuseDiskIo(listener)) {
                                mTextViewSetter.setPermanent("Third disk I/O overuse failed.");
                                finishTest(listener);
                                return;
                            }
                            mTextViewSetter.setPermanent(
                                    "Third disk I/O overuse completed successfully.");

                            finishTest(listener);
                            showAlert("Recurring I/O overuse test", "Test completed successfully.",
                                    0);
                            Log.d(TAG, "Recurring I/O overuse test completed.");
                        }));

        return view;
    }

    private boolean overuseDiskIo(IoOveruseListener listener) {
        DiskIoStats diskIoStats = fetchInitialDiskIoStats();
        if (diskIoStats == null) {
            return false;
        }
        Log.i(TAG, "Fetched initial disk I/O status: " + diskIoStats);

        /*
         * CarService notifies applications on exceeding 80% of the overuse threshold. The app maybe
         * notified before completing the following write. Ergo, the minimum expected written bytes
         * should be the warn threshold rather than the actual amount of bytes written by the app.
         */
        long minBytesWritten =
                (long) Math.ceil((diskIoStats.totalBytesWritten + diskIoStats.remainingBytes)
                        * WARN_THRESHOLD_PERCENT);
        listener.expectNewNotification(minBytesWritten, diskIoStats.totalOveruses,
                NOTIFICATION_TYPE_WARNING);
        long bytesToExceedWarnThreshold =
                (long) Math.ceil(diskIoStats.remainingBytes * EXCEED_WARN_THRESHOLD_PERCENT);
        if (!writeToDisk(bytesToExceedWarnThreshold) || !listener.isValidNotificationReceived()) {
            return false;
        }
        mTextViewSetter.setPermanent(
                "80% exceeding I/O overuse notification received successfully.");

        long remainingBytes = listener.getNotifiedRemainingBytes();
        listener.expectNewNotification(remainingBytes, diskIoStats.totalOveruses + 1,
                NOTIFICATION_TYPE_OVERUSE);
        if (!writeToDisk(remainingBytes) || !listener.isValidNotificationReceived()) {
            return false;
        }
        mTextViewSetter.setPermanent(
                "100% exceeding I/O overuse notification received successfully.");

        return true;
    }

    @Override
    public void onDestroyView() {
        FileUtils.deleteContentsAndDir(mTestDir);
        super.onDestroyView();
    }

    private @Nullable DiskIoStats fetchInitialDiskIoStats() {
        if (!writeToDisk(TEN_MEGABYTES)) {
            return null;
        }

        ResourceOveruseStats resourceOveruseStats = mCarWatchdogManager.getResourceOveruseStats(
                CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO,
                CarWatchdogManager.STATS_PERIOD_CURRENT_DAY);
        Log.d(TAG, "Stats fetched from watchdog manager: " + resourceOveruseStats);

        IoOveruseStats ioOveruseStats = resourceOveruseStats.getIoOveruseStats();
        if (ioOveruseStats == null) {
            showErrorAlert("No I/O overuse stats available for the application after writing "
                    + TEN_MEGABYTES + " bytes." + System.lineSeparator() + "Note: Start custom "
                    + "perf collection with 1 second interval before running the test.");
            return null;
        }
        if (ioOveruseStats.getTotalBytesWritten() < TEN_MEGABYTES) {
            showErrorAlert("Actual written bytes to disk '" + TEN_MEGABYTES
                    + "' is greater than total bytes written '"
                    + ioOveruseStats.getTotalBytesWritten() + "' returned by get request.");
            return null;
        }
        /*
         * Check for foreground mode bytes given kitchensink app is running in the foreground
         * during manual testing.
         */
        long remainingBytes = ioOveruseStats.getRemainingWriteBytes().getForegroundModeBytes();
        if (remainingBytes == 0) {
            showErrorAlert("Zero remaining bytes reported." + System.lineSeparator()
                    + "Note: Reset resource overuse stats before running the test.");
            return null;
        }
        return new DiskIoStats(ioOveruseStats.getTotalBytesWritten(), remainingBytes,
                ioOveruseStats.getTotalOveruses());
    }

    private IoOveruseListener addResourceOveruseListener() {
        IoOveruseListener listener = new IoOveruseListener();
        mCarWatchdogManager.addResourceOveruseListener(
                mActivity.getMainExecutor(), CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO, listener);
        return listener;
    }

    private void finishTest(IoOveruseListener listener) {
        if (FileUtils.deleteContents(mTestDir)) {
            Log.i(TAG, "Deleted contents of the test directory " + mTestDir.getAbsolutePath());
        } else {
            Log.e(TAG, "Failed to delete contents of the test directory "
                    + mTestDir.getAbsolutePath());
        }
        mCarWatchdogManager.removeResourceOveruseListener(listener);
    }

    private boolean writeToDisk(long bytes) {
        File uniqueFile = new File(mTestDir, Long.toString(System.nanoTime()));
        boolean result = writeToFile(uniqueFile, bytes);
        if (uniqueFile.delete()) {
            Log.i(TAG, "Deleted file: " + uniqueFile.getAbsolutePath());
        } else {
            Log.e(TAG, "Failed to delete file: " + uniqueFile.getAbsolutePath());
        }
        return result;
    }

    private boolean writeToFile(File uniqueFile, long bytes) {
        long writtenBytes = 0;
        try (FileOutputStream fos = new FileOutputStream(uniqueFile)) {
            Log.d(TAG, "Attempting to write " + bytes + " bytes");
            writtenBytes = writeToFos(fos, bytes);
            if (writtenBytes < bytes) {
                showErrorAlert("Failed to write '" + bytes + "' bytes to disk. '"
                        + writtenBytes + "' bytes were successfully written, while '"
                        + (bytes - writtenBytes)
                        + "' bytes were pending at the moment the exception occurred."
                        + System.lineSeparator()
                        + "Note: Clear the app's storage and rerun the test.");
                return false;
            }
            fos.getFD().sync();
            mTextViewSetter.set("Wrote " + bytes + " bytes to disk. Waiting "
                    + (DISK_DELAY_MS / 1000) + " seconds for the disk I/O activity to be detected "
                    + "by the watchdog service...");
            Thread.sleep(DISK_DELAY_MS);
            return true;
        } catch (IOException | InterruptedException e) {
            String reason;
            if (e instanceof IOException) {
                reason = "I/O exception";
            } else {
                reason = "Thread interrupted";
                Thread.currentThread().interrupt();
            }
            String message = reason + " after successfully writing to disk.";
            Log.e(TAG, message, e);
            showErrorAlert(message + System.lineSeparator() + System.lineSeparator()
                    + e.getMessage());
        }
        return false;
    }

    private long writeToFos(FileOutputStream fos, long remainingBytes) {
        long totalBytesWritten = 0;
        while (remainingBytes != 0) {
            int writeBytes =
                    (int) Math.min(Integer.MAX_VALUE,
                                    Math.min(Runtime.getRuntime().freeMemory(), remainingBytes));
            try {
                fos.write(new byte[writeBytes]);
            }  catch (InterruptedIOException e) {
                Thread.currentThread().interrupt();
                continue;
            } catch (IOException e) {
                Log.e(TAG, "I/O exception while writing " + writeBytes + " to disk", e);
                return totalBytesWritten;
            }
            totalBytesWritten += writeBytes;
            remainingBytes -= writeBytes;
            if (writeBytes > 0 && remainingBytes > 0) {
                Log.i(TAG, "Total bytes written: " + totalBytesWritten + "/"
                        + (totalBytesWritten + remainingBytes));
                mTextViewSetter.set("Wrote (" + totalBytesWritten + " / "
                        + (totalBytesWritten + remainingBytes) + ") bytes. Writing to disk...");
            }
        }
        Log.i(TAG, "Write completed.");
        return totalBytesWritten;
    }

    private void showErrorAlert(String message) {
        mTextViewSetter.setPermanent("Error: " + message);
        showAlert("Error", message, android.R.drawable.ic_dialog_alert);
    }

    private void showAlert(String title, String message, int iconDrawable) {
        mActivity.runOnUiThread(
                () -> {
                    SpannableString messageSpan = new SpannableString(message);
                    messageSpan.setSpan(new RelativeSizeSpan(1.3f), 0, message.length(), 0);
                    new AlertDialog.Builder(mContext)
                            .setTitle(title)
                            .setMessage(messageSpan)
                            .setPositiveButton(android.R.string.ok, null)
                            .setIcon(iconDrawable)
                            .show();
                });
    }

    private static String toNotificationTypeString(@NotificationType int type) {
        switch (type) {
            case NOTIFICATION_TYPE_WARNING:
                return "I/O overuse warning notification";
            case NOTIFICATION_TYPE_OVERUSE:
                return "I/O overuse exceeding notification";
        }
        return "Unknown notification type";
    }

    private final class IoOveruseListener
            implements CarWatchdogManager.ResourceOveruseListener {
        private static final int NOTIFICATION_DELAY_MS = 10000;

        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private @NotificationStatus int mNotificationStatus;
        @GuardedBy("mLock")
        private long mNotifiedRemainingBytes;
        @GuardedBy("mLock")
        private long mExpectedMinBytesWritten;
        @GuardedBy("mLock")
        private long mExceptedTotalOveruses;
        @GuardedBy("mLock")
        private @NotificationType int mExpectedNotificationType;

        @Override
        public void onOveruse(@NonNull ResourceOveruseStats resourceOveruseStats) {
            synchronized (mLock) {
                mLock.notifyAll();
                mNotificationStatus = NOTIFICATION_STATUS_INVALID;
                Log.d(TAG, "Stats received in the "
                        + toNotificationTypeString(mExpectedNotificationType) + ": "
                        + resourceOveruseStats);
                IoOveruseStats ioOveruseStats = resourceOveruseStats.getIoOveruseStats();
                if (ioOveruseStats == null) {
                    showErrorAlert("No I/O overuse stats reported for the application in the "
                            + toNotificationTypeString(mExpectedNotificationType) + '.');
                    return;
                }
                long totalBytesWritten = ioOveruseStats.getTotalBytesWritten();
                if (totalBytesWritten < mExpectedMinBytesWritten) {
                    showErrorAlert("Expected minimum bytes written '" + mExpectedMinBytesWritten
                            + "' is greater than total bytes written '" + totalBytesWritten
                            + "' reported in the "
                            + toNotificationTypeString(mExpectedNotificationType) + '.');
                    return;
                }
                mNotifiedRemainingBytes = ioOveruseStats.getRemainingWriteBytes()
                        .getForegroundModeBytes();
                if (mExpectedNotificationType == NOTIFICATION_TYPE_WARNING
                        && mNotifiedRemainingBytes == 0) {
                    showErrorAlert("Expected non-zero remaining write bytes in the "
                            + toNotificationTypeString(mExpectedNotificationType) + '.');
                    return;
                } else if (mExpectedNotificationType == NOTIFICATION_TYPE_OVERUSE
                        && mNotifiedRemainingBytes != 0) {
                    showErrorAlert("Expected zero remaining write bytes doesn't match remaining "
                            + "write bytes " + mNotifiedRemainingBytes + " reported in the "
                            + toNotificationTypeString(mExpectedNotificationType) + ".");
                    return;
                }
                long totalOveruses = ioOveruseStats.getTotalOveruses();
                if (totalOveruses != mExceptedTotalOveruses) {
                    showErrorAlert("Expected total overuses " + mExceptedTotalOveruses
                            + "doesn't match total overuses " + totalOveruses + " reported in the "
                            + toNotificationTypeString(mExpectedNotificationType) + '.');
                    return;
                }
                mNotificationStatus = NOTIFICATION_STATUS_VALID;
            }
        }

        public long getNotifiedRemainingBytes() {
            synchronized (mLock) {
                return mNotifiedRemainingBytes;
            }
        }

        public void expectNewNotification(long expectedMinBytesWritten, long expectedTotalOveruses,
                @NotificationType int notificationType) {
            synchronized (mLock) {
                mNotificationStatus = NOTIFICATION_STATUS_NO;
                mExpectedMinBytesWritten = expectedMinBytesWritten;
                mExceptedTotalOveruses = expectedTotalOveruses;
                mExpectedNotificationType = notificationType;
            }
        }

        private boolean isValidNotificationReceived() {
            synchronized (mLock) {
                long now = SystemClock.uptimeMillis();
                long deadline = now + NOTIFICATION_DELAY_MS;
                mTextViewSetter.set("Waiting " + (NOTIFICATION_DELAY_MS / 1000)
                                + " seconds to be notified of disk I/O overuse...");
                while (mNotificationStatus == NOTIFICATION_STATUS_NO && now < deadline) {
                    try {
                        mLock.wait(deadline - now);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        continue;
                    } finally {
                        now = SystemClock.uptimeMillis();
                    }
                    break;
                }
                mTextViewSetter.set("");
                if (mNotificationStatus == NOTIFICATION_STATUS_NO) {
                    showErrorAlert("No " + toNotificationTypeString(mExpectedNotificationType)
                            + " received.");
                }
                return mNotificationStatus == NOTIFICATION_STATUS_VALID;
            }
        }
    }

    private static final class DiskIoStats {
        public final long totalBytesWritten;
        public final long remainingBytes;
        public final long totalOveruses;

        DiskIoStats(long totalBytesWritten, long remainingBytes, long totalOveruses) {
            this.totalBytesWritten = totalBytesWritten;
            this.remainingBytes = remainingBytes;
            this.totalOveruses = totalOveruses;
        }

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("DiskIoStats{TotalBytesWritten: ").append(totalBytesWritten)
                    .append(", RemainingBytes: ").append(remainingBytes)
                    .append(", TotalOveruses: ").append(totalOveruses)
                    .append("}").toString();
        }
    }

    private static final class TextViewSetter {
        private final SpannableStringBuilder mPermanentSpannableString =
                new SpannableStringBuilder();
        private final TextView mTextView;
        private final KitchenSinkActivity mActivity;

        TextViewSetter(TextView textView, KitchenSinkActivity activity) {
            mTextView = textView;
            mActivity = activity;
        }

        private void setPermanent(CharSequence charSequence) {
            mPermanentSpannableString.append(System.lineSeparator()).append(charSequence);
            mActivity.runOnUiThread(() ->
                    mTextView.setText(new SpannableStringBuilder(mPermanentSpannableString)));
        }

        private void set(CharSequence charSequence) {
            mActivity.runOnUiThread(() ->
                    mTextView.setText(new SpannableStringBuilder(mPermanentSpannableString)
                            .append(System.lineSeparator())
                            .append(charSequence)));
        }
    }
}
