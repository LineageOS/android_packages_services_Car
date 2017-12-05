/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.car.storagemonitoring;

import android.annotation.SystemApi;
import android.car.storagemonitoring.UidIoStats.Metrics;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Delta of uid_io stats taken at a sample point.
 *
 * @hide
 */
@SystemApi
public class UidIoStatsDelta implements Parcelable {
    public static final Creator<UidIoStatsDelta> CREATOR = new Creator<UidIoStatsDelta>() {
        @Override
        public UidIoStatsDelta createFromParcel(Parcel in) {
            return new UidIoStatsDelta(in);
        }

        @Override
        public UidIoStatsDelta[] newArray(int size) {
            return new UidIoStatsDelta[size];
        }
    };

    private final List<UidIoStats> mStats;
    private final long mUptimeTimestamp;

    public UidIoStatsDelta(List<UidIoStats> stats, long timestamp) {
        mStats = stats;
        mUptimeTimestamp = timestamp;
    }

    public UidIoStatsDelta(Parcel in) {
        mStats = in.createTypedArrayList(UidIoStats.CREATOR);
        mUptimeTimestamp = in.readLong();
    }

    public UidIoStatsDelta(JSONObject in) throws JSONException {
        mUptimeTimestamp = in.getInt("uptime");
        JSONArray statsArray = in.getJSONArray("stats");
        mStats = new ArrayList<>();
        for(int i = 0; i < statsArray.length(); ++i) {
            mStats.add(new UidIoStats(statsArray.getJSONObject(i)));
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedList(mStats);
        dest.writeLong(mUptimeTimestamp);
    }

    public void writeToJson(JsonWriter jsonWriter) throws IOException {
        jsonWriter.beginObject();
        jsonWriter.name("uptime").value(mUptimeTimestamp);
        jsonWriter.name("stats").beginArray();
        for (UidIoStats stat : mStats) {
            stat.writeToJson(jsonWriter);
        }
        jsonWriter.endArray();
        jsonWriter.endObject();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public long getTimestamp() {
        return mUptimeTimestamp;
    }

    public List<UidIoStats> getStats() {
        return mStats;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStats, mUptimeTimestamp);
    }

    public UidIoStats getUserIdStats(int uid) {
        for (UidIoStats stats : getStats()) {
            if (stats.uid == uid) {
                return stats;
            }
        }

        return null;
    }

    public UidIoStats.Metrics getForegroundTotals() {
        long bytesRead = 0;
        long bytesWritten = 0;
        long bytesReadFromStorage = 0;
        long bytesWrittenToStorage = 0;
        long fsyncCalls = 0;

        for (UidIoStats stats : getStats()) {
            bytesRead += stats.foreground.bytesRead;
            bytesWritten += stats.foreground.bytesWritten;
            bytesReadFromStorage += stats.foreground.bytesReadFromStorage;
            bytesWrittenToStorage += stats.foreground.bytesWrittenToStorage;
            fsyncCalls += stats.foreground.fsyncCalls;
        }

        return new Metrics(bytesRead,
                bytesWritten,
                bytesReadFromStorage,
                bytesWrittenToStorage,
                fsyncCalls);
    }

    public UidIoStats.Metrics getBackgroundTotals() {
        long bytesRead = 0;
        long bytesWritten = 0;
        long bytesReadFromStorage = 0;
        long bytesWrittenToStorage = 0;
        long fsyncCalls = 0;

        for (UidIoStats stats : getStats()) {
            bytesRead += stats.background.bytesRead;
            bytesWritten += stats.background.bytesWritten;
            bytesReadFromStorage += stats.background.bytesReadFromStorage;
            bytesWrittenToStorage += stats.background.bytesWrittenToStorage;
            fsyncCalls += stats.background.fsyncCalls;
        }

        return new Metrics(bytesRead,
            bytesWritten,
            bytesReadFromStorage,
            bytesWrittenToStorage,
            fsyncCalls);
    }

    public UidIoStats.Metrics getTotals() {
        UidIoStats.Metrics foreground = getForegroundTotals();
        UidIoStats.Metrics background = getBackgroundTotals();

        return new UidIoStats.Metrics(foreground.bytesRead + background.bytesRead,
                foreground.bytesWritten + background.bytesWritten,
                foreground.bytesReadFromStorage + background.bytesReadFromStorage,
                foreground.bytesWrittenToStorage + background.bytesWrittenToStorage,
                foreground.fsyncCalls + background.fsyncCalls);
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof UidIoStatsDelta) {
            UidIoStatsDelta delta = (UidIoStatsDelta)other;
            return delta.getTimestamp() == getTimestamp() &&
                delta.getStats().equals(getStats());
        }
        return false;
    }

    @Override
    public String toString() {
        StringJoiner stringJoiner = new StringJoiner(", ");
        for (UidIoStats stats : getStats()) {
            stringJoiner.add(stats.toString());
        }
        return "timestamp = " + getTimestamp() + ", stats = " + stringJoiner.toString();
    }
}
