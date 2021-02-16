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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.util.Log;

import com.android.car.telemetry.TelemetryProto.LogListener;
import com.android.car.telemetry.TelemetryProto.Manifest;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Encapsulates a filter that can be matched on log entries. Contains builder function to create
 * list of LogFilter given the loglistener.
 */
public final class LogFilter {

    private static final String TAG = LogFilter.class.getSimpleName();

    /**
     * Types of filters to match against the logs. Different filter types will match in different
     * ways.
     */
    @Retention(SOURCE)
    @IntDef({FILTER_TAG, SUBSTRING})
    @interface FilterType {}
    static final int FILTER_TAG = 0;
    static final int SUBSTRING = 1;

    private final int mLogListenerIndex;
    private final @FilterType int mFilterType;
    private final String mFilter;

    LogFilter(int logListenerIndex, @FilterType int filterType, String filter) {
        this.mLogListenerIndex = logListenerIndex;
        this.mFilterType = filterType;
        if (filter == null) {
            throw new NullPointerException("Null filter");
        }
        this.mFilter = filter;
    }

    int getLogListenerIndex() {
        return mLogListenerIndex;
    }

    @LogFilter.FilterType int getFilterType() {
        return mFilterType;
    }

    String getFilter() {
        return mFilter;
    }

    /**
     * Creates a LogFilter instance.
     *
     * @param logListenerIndex the index of the logListener associated with the filter.
     * @param filterType the type of filter.
     * @param filter the value of the filter.
     * @return created LogFilter instance.
     */
    static LogFilter create(int logListenerIndex, @FilterType int filterType, String filter) {
        return new LogFilter(logListenerIndex, filterType, filter);
    }

    /**
     * Builds a List of {@link LogFilter} instances from {@link Manifest} and the logListener name.
     *
     * @param manifest {@link Manifest} file that contains list of logListeners.
     * @param logListenerName the name of the logListener as registered in the {@link Manifest}.
     * @return List of {@link LogFilter} instances.
     */
    static List<LogFilter> buildFilters(Manifest manifest, String logListenerName) {
        int logListenerIndex = getLogListenerIndexFromName(manifest, logListenerName);
        if (logListenerIndex == -1) {
            Log.w(TAG, "log listener with name " + logListenerName
                    + " does not exist in manifest.");
            return Collections.unmodifiableList(new ArrayList<>());
        }

        List<LogFilter> result = new ArrayList<>();
        result.addAll(
                manifest.getLogListeners(logListenerIndex).getTagsList().stream()
                .map(tag -> LogFilter.create(logListenerIndex, FILTER_TAG, tag))
                .collect(Collectors.toList()));
        result.addAll(
                manifest.getLogListeners(logListenerIndex).getTypesList().stream()
                .map(LogFilter::typeToFilter)
                .filter(Optional::isPresent)
                .map(filter ->
                        LogFilter.create(logListenerIndex, SUBSTRING, filter.get()))
                .collect(Collectors.toList()));
        return Collections.unmodifiableList(result);
    }

    /**
     * Gets the index of the {@link LogListener} in the manifest's list of logListeners.
     * Returns -1 if not found.
     *
     * @param manifest the {@link Manifest} that contains the definitions of the logListeners.
     * @param logListenerName name of the {@link LogListener} to get index for.
     * @return index of the logListener, -1 if not found.
     */
    static int getLogListenerIndexFromName(Manifest manifest, String logListenerName) {
        for (int i = 0; i < manifest.getLogListenersCount(); i++) {
            LogListener listener = manifest.getLogListeners(i);
            if (listener.getName().equals(logListenerName)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Converts log message filter {@link LogListener.Type} to corresponding filter string.
     *
     * @param type the filter {@link LogListener.Type}.
     * @return the corresponding filter string for the logListener type.
     */
    private static Optional<String> typeToFilter(LogListener.Type type) {
        switch (type) {
            case TYPE_UNSPECIFIED:
                return Optional.empty();
            case EXCEPTIONS:
                return Optional.of("Exception: ");
        }
        return Optional.empty();
    }

    /**
     * Matches in different ways against {@link LogcatReader.LogEntry} components depending on
     * filterType.
     *
     * @param entry the {@link LogcatReader.LogEntry} whose components will be matched against the
     *       filters.
     * @return boolean denoting whether the filter can match the {@link LogcatReader.LogEntry}.
     */
    boolean matches(LogcatReader.LogEntry entry) {
        switch (getFilterType()) {
            case FILTER_TAG:
                return entry.mTag.equals(getFilter());
            case SUBSTRING:
                return entry.mMessage.contains(getFilter());
        }
        return false;
    }

    @Override
    public String toString() {
        return "LogFilter{"
                + "mLogListenerIndex=" + mLogListenerIndex + ", "
                + "mFilterType=" + mFilterType + ", "
                + "mFilter=" + mFilter
                + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof LogFilter) {
            LogFilter that = (LogFilter) o;
            return this.mLogListenerIndex == that.getLogListenerIndex()
                    && this.mFilterType == that.getFilterType()
                    && this.mFilter.equals(that.getFilter());
        }
        return false;
    }

    @Override
    public int hashCode() {
        int hash = 1;
        hash *= 1000003;
        hash ^= mLogListenerIndex;
        hash *= 1000003;
        hash ^= Integer.hashCode(mFilterType);
        hash *= 1000003;
        hash ^= mFilter.hashCode();
        return hash;
    }
}

