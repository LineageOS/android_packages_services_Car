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
package com.android.car.bugreport;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.google.auto.value.AutoValue;

import java.lang.annotation.Retention;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Represents the information that a bugreport can contain. */
@AutoValue
abstract class MetaBugReport implements Parcelable {

    private static final DateFormat BUG_REPORT_TIMESTAMP_DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

    /** Contains {@link #TYPE_SILENT} and audio message. */
    static final int TYPE_INTERACTIVE = 0;

    /**
     * Contains dumpstate and screenshots.
     *
     * <p>Silent bugreports are not uploaded automatically. The app asks user to add audio
     * message either through notification or {@link BugReportInfoActivity}.
     */
    static final int TYPE_SILENT = 1;

    /** Annotation for bug report types. */
    @Retention(SOURCE)
    @IntDef({TYPE_INTERACTIVE, TYPE_SILENT})
    @interface BugReportType {};

    /**
     * @return Id of the bug report. Bug report id monotonically increases and is unique.
     */
    public abstract int getId();

    /**
     * @return Username (LDAP) that created this bugreport
     */
    public abstract String getUserName();

    /**
     * @return Title of the bug.
     */
    public abstract String getTitle();

    /**
     * @return Timestamp when the bug report is initialized.
     */
    public abstract String getTimestamp();

    /**
     * @return Timestamp converted to {@link Date}.
     */
    public Date getTimestampDate() {
        try {
            return BUG_REPORT_TIMESTAMP_DATE_FORMAT.parse(getTimestamp());
        } catch (ParseException e) {
            Log.e(this.getClass().getSimpleName(), "Failed to parse timestamp", e);
            return new Date(0); // Return "January 1, 1970, 00:00:00 GMT".
        }
    }

    /**
     * @return path to the zip file
     */
    public abstract String getFilePath();

    /**
     * @return {@link Status} of the bug upload.
     */
    public abstract int getStatus();

    /**
     * @return StatusMessage of the bug upload.
     */
    public abstract String getStatusMessage();

    /**
     * @return {@link BugReportType}.
     */
    public abstract int getType();

    /** @return {@link Builder} from the meta bug report. */
    public abstract Builder toBuilder();

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(getId());
        dest.writeString(getTimestamp());
        dest.writeString(getTitle());
        dest.writeString(getUserName());
        dest.writeString(getFilePath());
        dest.writeInt(getStatus());
        dest.writeString(getStatusMessage());
        dest.writeInt(getType());
    }

    /** Converts {@link Date} to bugreport timestamp. */
    static String toBugReportTimestamp(Date date) {
        return BUG_REPORT_TIMESTAMP_DATE_FORMAT.format(date);
    }

    /** Creates a {@link Builder} with default, non-null values. */
    static Builder builder() {
        return new AutoValue_MetaBugReport.Builder()
                .setTimestamp("")
                .setFilePath("")
                .setStatusMessage("")
                .setTitle("")
                .setUserName("");
    }

    /** A creator that's used by Parcelable. */
    public static final Parcelable.Creator<MetaBugReport> CREATOR =
            new Parcelable.Creator<MetaBugReport>() {
                public MetaBugReport createFromParcel(Parcel in) {
                    int id = in.readInt();
                    String timestamp = in.readString();
                    String title = in.readString();
                    String username = in.readString();
                    String filePath = in.readString();
                    int status = in.readInt();
                    String statusMessage = in.readString();
                    int type = in.readInt();
                    return MetaBugReport.builder()
                            .setId(id)
                            .setTimestamp(timestamp)
                            .setTitle(title)
                            .setUserName(username)
                            .setFilePath(filePath)
                            .setStatus(status)
                            .setStatusMessage(statusMessage)
                            .setType(type)
                            .build();
                }

                public MetaBugReport[] newArray(int size) {
                    return new MetaBugReport[size];
                }
            };

    /** Builder for MetaBugReport. */
    @AutoValue.Builder
    abstract static class Builder {
        /** Sets id. */
        public abstract Builder setId(int id);

        /** Sets timestamp. */
        public abstract Builder setTimestamp(String timestamp);

        /** Sets title. */
        public abstract Builder setTitle(String title);

        /** Sets username. */
        public abstract Builder setUserName(String username);

        /** Sets filepath. */
        public abstract Builder setFilePath(String filePath);

        /** Sets {@link Status}. */
        public abstract Builder setStatus(int status);

        /** Sets statusmessage. */
        public abstract Builder setStatusMessage(String statusMessage);

        /** Sets the {@link BugReportType}. */
        public abstract Builder setType(@BugReportType int type);

        public abstract MetaBugReport build();
    }
}
