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

import com.google.common.base.Strings;

import java.lang.annotation.Retention;

/** Represents the information that a bugreport can contain. */
public final class MetaBugReport implements Parcelable {

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

    private final int mId;
    private final String mTimestamp;
    private final String mTitle;
    private final String mUsername;
    private final String mFilePath;
    private final int mStatus;
    private final String mStatusMessage;
    /** One of {@link BugReportType}. */
    private final int mType;

    private MetaBugReport(Builder builder) {
        mId = builder.mId;
        mTimestamp = builder.mTimestamp;
        mTitle = builder.mTitle;
        mUsername = builder.mUsername;
        mFilePath = builder.mFilePath;
        mStatus = builder.mStatus;
        mStatusMessage = builder.mStatusMessage;
        mType = builder.mType;
    }

    /**
     * @return Id of the bug report. Bug report id monotonically increases and is unique.
     */
    public int getId() {
        return mId;
    }

    /**
     * @return Username (LDAP) that created this bugreport
     */
    public String getUsername() {
        return Strings.nullToEmpty(mUsername);
    }

    /**
     * @return Title of the bug.
     */
    public String getTitle() {
        return Strings.nullToEmpty(mTitle);
    }

    /**
     * @return Timestamp when the bug report is initialized.
     */
    public String getTimestamp() {
        return Strings.nullToEmpty(mTimestamp);
    }

    /**
     * @return path to the zip file
     */
    public String getFilePath() {
        return Strings.nullToEmpty(mFilePath);
    }

    /**
     * @return {@link Status} of the bug upload.
     */
    public int getStatus() {
        return mStatus;
    }

    /**
     * @return StatusMessage of the bug upload.
     */
    public String getStatusMessage() {
        return Strings.nullToEmpty(mStatusMessage);
    }

    /**
     * @return {@link BugReportType}.
     */
    public int getType() {
        return mType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** Returns {@link Builder} from the meta bug report. */
    public Builder toBuilder() {
        return new Builder(mId, mTimestamp)
                .setFilepath(mFilePath)
                .setStatus(mStatus)
                .setStatusMessage(mStatusMessage)
                .setTitle(mTitle)
                .setUserName(mUsername)
                .setType(mType);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeString(mTimestamp);
        dest.writeString(mTitle);
        dest.writeString(mUsername);
        dest.writeString(mFilePath);
        dest.writeInt(mStatus);
        dest.writeString(mStatusMessage);
        dest.writeInt(mType);
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
                    return new Builder(id, timestamp)
                            .setTitle(title)
                            .setUserName(username)
                            .setFilepath(filePath)
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
    public static class Builder {
        private final int mId;
        private final String mTimestamp;
        private String mTitle;
        private String mUsername;
        private String mFilePath;
        private int mStatus;
        private String mStatusMessage;
        private int mType;

        /**
         * Initializes MetaBugReport.Builder.
         *
         * @param id        - mandatory bugreport id
         * @param timestamp - mandatory timestamp when bugreport initialized.
         */
        public Builder(int id, String timestamp) {
            mId = id;
            mTimestamp = timestamp;
        }

        /** Sets title. */
        public Builder setTitle(String title) {
            mTitle = title;
            return this;
        }

        /** Sets username. */
        public Builder setUserName(String username) {
            mUsername = username;
            return this;
        }

        /** Sets filepath. */
        public Builder setFilepath(String filePath) {
            mFilePath = filePath;
            return this;
        }

        /** Sets {@link Status}. */
        public Builder setStatus(int status) {
            mStatus = status;
            return this;
        }

        /** Sets statusmessage. */
        public Builder setStatusMessage(String statusMessage) {
            mStatusMessage = statusMessage;
            return this;
        }

        /** Sets the {@link BugReportType}. */
        public Builder setType(@BugReportType int type) {
            mType = type;
            return this;
        }

        /** Returns a {@link MetaBugReport}. */
        public MetaBugReport build() {
            return new MetaBugReport(this);
        }
    }
}
