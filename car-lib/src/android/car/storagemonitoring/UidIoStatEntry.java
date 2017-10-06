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
import android.os.Parcel;
import android.os.Parcelable;
import android.util.JsonWriter;
import java.io.IOException;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * uid_io stats about one user ID.
 *
 * Contains information about I/O activity that can be attributed to processes running on
 * behalf of one user of the system, as collected by the kernel.
 *
 * @hide
 */
@SystemApi
public final class UidIoStatEntry implements Parcelable {

    public static final Parcelable.Creator<UidIoStatEntry> CREATOR =
        new Parcelable.Creator<UidIoStatEntry>() {
            public UidIoStatEntry createFromParcel(Parcel in) {
                return new UidIoStatEntry(in);
            }

            public UidIoStatEntry[] newArray(int size) {
                return new UidIoStatEntry[size];
            }
        };

    /**
     * The user id that this object contains metrics for.
     *
     * In many cases this can be converted to a list of Java app packages installed on the device.
     * In other cases, the user id can refer to either the kernel itself (uid 0), or low-level
     * system services that are running entirely natively.
     */
    public final int uid;

    /**
     * Statistics for apps running in foreground.
     */
    public final PerStateMetrics foreground;

    /**
     * Statistics for apps running in background.
     */
    public final PerStateMetrics background;

    public UidIoStatEntry(int uid, PerStateMetrics foreground, PerStateMetrics background) {
        this.uid = uid;
        this.foreground = Objects.requireNonNull(foreground);
        this.background = Objects.requireNonNull(background);
    }

    public UidIoStatEntry(Parcel in) {
        uid = in.readInt();
        foreground = in.readParcelable(PerStateMetrics.class.getClassLoader());
        background = in.readParcelable(PerStateMetrics.class.getClassLoader());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(uid);
        dest.writeParcelable(foreground, flags);
        dest.writeParcelable(background, flags);
    }

    public void writeToJson(JsonWriter jsonWriter) throws IOException {
        jsonWriter.beginObject();
        jsonWriter.name("uid").value(uid);
        jsonWriter.name("foreground"); foreground.writeToJson(jsonWriter);
        jsonWriter.name("background"); background.writeToJson(jsonWriter);
        jsonWriter.endObject();
    }

    public UidIoStatEntry(JSONObject in) throws JSONException {
        uid = in.getInt("uid");
        foreground = new PerStateMetrics(in.getJSONObject("foreground"));
        background = new PerStateMetrics(in.getJSONObject("background"));
    }

    /**
     * Returns the difference between the values stored in this object vs. those
     * stored in other.
     *
     * It is the same as doing a delta() on foreground and background, plus verifying that
     * both objects refer to the same uid.
     *
     * @hide
     */
    public UidIoStatEntry delta(UidIoStatEntry other) {
        if (uid != other.uid) {
            throw new IllegalArgumentException("cannot calculate delta between different user IDs");
        }
        return new UidIoStatEntry(uid,
                foreground.delta(other.foreground), background.delta(other.background));
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof UidIoStatEntry) {
            UidIoStatEntry uidIoStatEntry = (UidIoStatEntry)other;

            return uid == uidIoStatEntry.uid &&
                    foreground.equals(uidIoStatEntry.foreground) &&
                    background.equals(uidIoStatEntry.background);
        }

        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(uid, foreground, background);
    }

    @Override
    public String toString() {
        return String.format("uid = %d, foreground = %s, background = %s",
            uid, foreground, background);
    }

    /**
     * I/O activity metrics that pertain to either the foreground or the background state.
     */
    public static final class PerStateMetrics implements Parcelable {

        public static final Parcelable.Creator<PerStateMetrics> CREATOR =
            new Parcelable.Creator<PerStateMetrics>() {
                public PerStateMetrics createFromParcel(Parcel in) {
                    return new PerStateMetrics(in);
                }

                public PerStateMetrics[] newArray(int size) {
                    return new PerStateMetrics[size];
                }
            };

        /**
         * Total bytes that processes running on behalf of this user obtained
         * via read() system calls.
         */
        public final long bytesRead;

        /**
         * Total bytes that processes running on behalf of this user transferred
         * via write() system calls.
         */
        public final long bytesWritten;

        /**
         * Total bytes that processes running on behalf of this user obtained
         * via read() system calls that actually were served by physical storage.
         */
        public final long bytesReadFromStorage;

        /**
         * Total bytes that processes running on behalf of this user transferred
         * via write() system calls that were actually sent to physical storage.
         */
        public final long bytesWrittenToStorage;

        /**
         * Total number of fsync() system calls that processes running on behalf of this user made.
         */
        public final long fsyncCalls;

        public PerStateMetrics(long bytesRead, long bytesWritten, long bytesReadFromStorage,
            long bytesWrittenToStorage, long fsyncCalls) {
            this.bytesRead = bytesRead;
            this.bytesWritten = bytesWritten;
            this.bytesReadFromStorage = bytesReadFromStorage;
            this.bytesWrittenToStorage = bytesWrittenToStorage;
            this.fsyncCalls = fsyncCalls;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeLong(bytesRead);
            dest.writeLong(bytesWritten);
            dest.writeLong(bytesReadFromStorage);
            dest.writeLong(bytesWrittenToStorage);
            dest.writeLong(fsyncCalls);
        }

        public void writeToJson(JsonWriter jsonWriter) throws IOException {
            jsonWriter.beginObject();
            jsonWriter.name("bytesRead").value(bytesRead);
            jsonWriter.name("bytesWritten").value(bytesWritten);
            jsonWriter.name("bytesReadFromStorage").value(bytesReadFromStorage);
            jsonWriter.name("bytesWrittenToStorage").value(bytesWrittenToStorage);
            jsonWriter.name("fsyncCalls").value(fsyncCalls);
            jsonWriter.endObject();
        }

        public PerStateMetrics(Parcel in) {
            bytesRead = in.readLong();
            bytesWritten = in.readLong();
            bytesReadFromStorage = in.readLong();
            bytesWrittenToStorage = in.readLong();
            fsyncCalls = in.readLong();
        }

        public PerStateMetrics(JSONObject in) throws JSONException {
            bytesRead = in.getLong("bytesRead");
            bytesWritten = in.getLong("bytesWritten");
            bytesReadFromStorage = in.getLong("bytesReadFromStorage");
            bytesWrittenToStorage = in.getLong("bytesWrittenToStorage");
            fsyncCalls = in.getLong("fsyncCalls");
        }

        /**
         * Computes the difference between the values stored in this object
         * vs. those stored in other
         *
         * It is the same as doing
         * new PerStateMetrics(bytesRead-other.bytesRead,bytesWritten-other.bytesWritten, ...)
         *
         * @hide
         */
        public PerStateMetrics delta(PerStateMetrics other) {
            return new PerStateMetrics(bytesRead-other.bytesRead,
                bytesWritten-other.bytesWritten,
                bytesReadFromStorage-other.bytesReadFromStorage,
                bytesWrittenToStorage-other.bytesWrittenToStorage,
                fsyncCalls-other.fsyncCalls);
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof PerStateMetrics) {
                PerStateMetrics perStateMetrics = (PerStateMetrics)other;

                return (bytesRead == perStateMetrics.bytesRead) &&
                    (bytesWritten == perStateMetrics.bytesWritten) &&
                    (bytesReadFromStorage == perStateMetrics.bytesReadFromStorage) &&
                    (bytesWrittenToStorage == perStateMetrics.bytesWrittenToStorage) &&
                    (fsyncCalls == perStateMetrics.fsyncCalls);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(bytesRead, bytesWritten, bytesReadFromStorage,
                bytesWrittenToStorage, fsyncCalls);
        }

        @Override
        public String toString() {
            return String.format("bytesRead=%d, bytesWritten=%d, bytesReadFromStorage=%d, bytesWrittenToStorage=%d, fsyncCalls=%d",
                bytesRead, bytesWritten, bytesReadFromStorage, bytesWrittenToStorage, fsyncCalls);
        }
    }
}
