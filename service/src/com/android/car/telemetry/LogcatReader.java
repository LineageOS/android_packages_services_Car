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
package com.android.car.telemetry;

import android.annotation.Nullable;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;

/**
 * Reads Android logs while there are log listeners registered to it and sends the event through the
 * mLogEventConsumer.
 */
public class LogcatReader {
    private static final String TAG = LogcatReader.class.getSimpleName();

    // TODO(b/180515554). Find a proper place for LOG_* constants.
    // They will be used in ScriptExecutor as well.
    /** The value of key to retrieve log seconds since epoch time from bundle. */
    private static final String LOG_SEC_KEY = "log.sec";

    /** The value of key to retrieve log nanoseconds from bundle. */
    private static final String LOG_NSEC_KEY = "log.nsec";

    /** The value of key to retrieve log tag from bundle. */
    private static final String LOG_TAG_KEY = "log.tag";

    /** The value of key to retrieve log message from bundle. */
    private static final String LOG_MESSAGE_KEY = "log.message";

    // Defined in system/core/liblog/include/log/log_read.h
    private static final int LOGGER_ENTRY_MAX_LEN = 5 * 1024;

    // The entry sizes differ in Android 10. See
    // https://cs.android.com/android/platform/superproject/+/android-10.0.0_r30:system/core/liblog/include/log/log_read.h
    public static final int ENTRY_V1_SIZE = 20;
    public static final int ENTRY_V2_V3_SIZE = 24;
    public static final int ENTRY_V4_SIZE = 28;

    private final AtomicBoolean mRunning = new AtomicBoolean(false);

    private final ObjIntConsumer<Bundle> mLogEventConsumer;
    private final Supplier<LocalSocket> mLocalSocketSupplier;
    private final Executor mExecutor;

    private final HashSet<LogFilter> mLogFilters = new HashSet<>();

    private synchronized boolean logFiltersEmpty() {
        return mLogFilters.isEmpty();
    }

    /**
     * Replicates {@code struct log_msg} from system/core/liblog/include/log/log_read.h and {@code
     * struct AndroidLogEntry} from system/core/liblog/include/log/logprint.h.
     */
    static class LogEntry {
        long mTvSec; // seconds since Epoch
        long mTvNSec; // nanoseconds
        int mPid; // generating process's pid
        long mTid; // generating process's tid
        long mUid; // generating process's uid
        int mPriority; // log priority, e.g. {@link Log#INFO}.
        String mTag;
        String mMessage;

        /**
         * Parses raw bytes received from {@code logd}.
         *
         * @param data raw bytes
         * @param readSize number of bytes received from logd.
         */
        @Nullable
        static LogEntry parse(byte[] data, int readSize) {
            // Parsing log_msg struct defined in system/core/liblog/include/log/log_read.h.
            // Only first headerSize is used to create LogEntry. Following message messageSize bytes
            // define log message.
            ByteBuffer dataBytes = ByteBuffer.wrap(data);
            dataBytes.order(ByteOrder.LITTLE_ENDIAN);
            int messageSize = dataBytes.getShort();
            int headerSize = dataBytes.getShort();
            if (readSize < messageSize + headerSize) {
                Log.w(
                        TAG, "Invalid log message size "
                        + (messageSize + headerSize)
                        + ", received only "
                        + readSize);
                return null;
            }
            LogEntry entry = new LogEntry();
            entry.mPid = dataBytes.getInt();
            entry.mTid = dataBytes.getInt();
            entry.mTvSec = dataBytes.getInt();
            entry.mTvNSec = dataBytes.getInt();
            if (headerSize >= ENTRY_V2_V3_SIZE) {
                dataBytes.position(dataBytes.position() + 4); // lid is not used here.
            }
            if (headerSize >= ENTRY_V4_SIZE) {
                entry.mUid = dataBytes.getInt();
            }

            // Parsing log message.
            // See android_log_processLogBuffer() in system/core/liblog/logprint.cpp for details.
            // message format: <priority:1><tag:N>\0<message:N>\0
            // TODO(b/180516393): improve message parsing that were not transferred
            // from the cpp above. Also verify this mechanism is ok from selinux perspective.

            if (messageSize < 3) {
                Log.w(TAG, "Log message is too small, size=" + messageSize);
                return null;
            }
            if (headerSize != dataBytes.position()) {
                Log.w(TAG, "Invalid header size " + headerSize + ", expected "
                        + dataBytes.position());
                return null;
            }
            int msgStart = -1;
            int msgEnd = -1;
            for (int i = 1; i < messageSize; i++) {
                if (data[headerSize + i] == 0) {
                    if (msgStart == -1) {
                        msgStart = i + 1 + headerSize;
                    } else {
                        msgEnd = i + headerSize;
                        break;
                    }
                }
            }
            if (msgStart == -1) {
                Log.w(TAG, "Invalid log message");
                return null;
            }
            if (msgEnd == -1) {
                msgEnd = Math.max(msgStart, messageSize - 1);
            }
            entry.mPriority = data[headerSize];
            entry.mTag =
                new String(data, headerSize + 1, msgStart - headerSize - 2,
                    StandardCharsets.US_ASCII);
            entry.mMessage =
                    new String(data, msgStart, msgEnd - msgStart, StandardCharsets.US_ASCII);
            return entry;
        }
    }

    /**
     * Constructs {@link LogcatReader}.
     *
     * @param logEventConsumer a consumer that's called when a filter matches a log.
     * @param localSocketSupplier a supplier for LocalSocket to connect logd.
     * @param executor an {@link Executor} to run the LogcatReader instance.
     */
    public LogcatReader(
            ObjIntConsumer<Bundle> logEventConsumer,
            Supplier<LocalSocket> localSocketSupplier,
            Executor executor) {
        this.mLogEventConsumer = logEventConsumer;
        this.mLocalSocketSupplier = localSocketSupplier;
        this.mExecutor = executor;
    }

    /** Runs {@link LogcatReader}. */
    private void run() {
        Log.d(TAG, "Running LogcatReader");

        // Under the hood, logcat receives logs from logd, to remove the middleman, LogcatReader
        // doesn't run logcat, but directly connects to logd socket and parses raw logs.
        try (LocalSocket socket = mLocalSocketSupplier.get()) {
            // Connect to /dev/socket/logdr
            socket.connect(new LocalSocketAddress("logdr", LocalSocketAddress.Namespace.RESERVED));
            try (OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream());
                  InputStream reader = socket.getInputStream()) {
                // Ask for streaming log and set tail=1 to get only new logs.
                // See system/core/liblog/logd_reader.cpp for example on how to interact with logdr.
                writer.write("stream tail=1");
                writer.flush();
                Log.d(TAG, "Sent request to logd and awaiting for logs");

                byte[] data = new byte[LOGGER_ENTRY_MAX_LEN + 1];
                while (!logFiltersEmpty()) {
                    int n = reader.read(data, 0, LOGGER_ENTRY_MAX_LEN);
                    if (n == -1) {
                        Log.e(TAG, "Disconnected from logd");
                        return;
                    }
                    LogEntry entry = LogEntry.parse(data, n);
                    if (entry == null) {
                        continue;
                    }

                    // Ignore the logs from the telemetry service. This
                    // makes sure a recursive log storm does not happen - i.e. app produces a log
                    // which in turn executes a code path that produces another log.
                    if (entry.mUid == Process.myUid()) {
                        continue;
                    }
                    // Check if it's running before processing the logs, because by the time we get
                    // here ScriptExecutor might get disconnected.
                    if (!mRunning.get()) {
                        Log.d(TAG, "Not running anymore, exiting.");
                        return;
                    }
                    // Keep track of which logListener an event for this entry has been sent,
                    // so that the same entry isn't sent multiple times for the same logListener
                    // if its multiple filters match.
                    HashSet<Integer> sentLogListenerIndices = new HashSet<>();
                    for (LogFilter filter : mLogFilters) {
                        if (!sentLogListenerIndices.contains(filter.getLogListenerIndex())
                                && filter.matches(entry)) {
                            sentLogListenerIndices.add(filter.getLogListenerIndex());
                            sendLogEvent(filter.getLogListenerIndex(), entry);
                        }
                    }
                }
                Log.d(TAG, "Log filters are empty, exiting.");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to connect to logd", e);
        } finally {
            mRunning.set(false);
        }
    }

    /**
     * Sends the log event to the through the mLogEventConsumer.
     *
     * @param logListenerIndex the index of the logListener, whose function will receive the log
     *     event.
     * @param entry the LogEntry instance to be bundled up and sent as event.
     */
    private void sendLogEvent(int logListenerIndex, LogEntry entry) {
        Bundle event = new Bundle();
        event.putLong(LOG_SEC_KEY, entry.mTvSec);
        event.putLong(LOG_NSEC_KEY, entry.mTvNSec);
        event.putString(LOG_TAG_KEY, entry.mTag);
        event.putString(LOG_MESSAGE_KEY, entry.mMessage);
        mLogEventConsumer.accept(event, logListenerIndex);
    }

    /**
     * Subscribes the list of {@link LogFilter} instances.
     *
     * @param newLogFilters the list of new log filters to be added to mLogFilters.
     */
    synchronized void subscribeLogFilters(List<LogFilter> newLogFilters) {
        mLogFilters.addAll(newLogFilters);
    }

    /**
     * Unsubscribes all {@link LogFilter} associated with the logListenerIndex
     *
     * @param logListenerIndex the index of the logListener to unregister.
     */
    synchronized void unsubscribeLogListener(int logListenerIndex) {
        mLogFilters.removeIf(lf -> lf.getLogListenerIndex() == logListenerIndex);
    }

    /** Starts the run method in a new thread if logcatReader isn't running already. */
    void startAsyncIfNotStarted() {
        if (mRunning.compareAndSet(false, true)) {
            mExecutor.execute(this::run);
        }
    }

    /** Gracefully stops {@link LogcatReader}. */
    synchronized void stop() {
        mRunning.set(false);
        mLogFilters.clear();
    }

    /** Builds a {@link LocalSocket} to read from {@code logdr}. */
    static LocalSocket buildLocalSocket() {
        return new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
    }
}
