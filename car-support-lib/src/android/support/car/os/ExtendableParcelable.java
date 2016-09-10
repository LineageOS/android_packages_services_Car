/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.support.car.os;

import android.os.Parcel;
import android.os.Parcelable;
import android.support.car.annotation.VersionDef;

/**
 * Ensures Parcelable can be extended in a future-proof way by writing version code and payload
 * length when writing to Parcel. When the reader reads the version, if <strong>writer version <=
 * reader version</strong>, the reader should know up to which element is supported by the writer
 * (and read only to that portion). If <strong>writer version > reader version</strong>, the
 * reader reads all members it knows and discards remaining data (so additional Parcel reading can
 * be done safely).
 * </p>
 * Reader instructions:
 * <ol>
 * <li>In constructor with Parcel, call super(Parcel) first so the version number and payload
 * length is read properly.</li>
 * <li>Call {@link #readHeader(Parcel)}.</li>
 * <li>After finishing all recognized or available members, call
 *    {@link #completeReading(Parcel, int)} with second argument set to return value from
 *    {@link #readHeader(Parcel)}. This discards any unread data.</li></ol>
 * Writer instructions (when implementing writeToParcel):
 * <ol>
 * <li>Call {@link #writeHeader(Parcel)} before writing anything else.</li>
 * <li>Call {@link #completeWriting(Parcel, int)} with second argument set to return value from
 *     {@link #writeHeader(Parcel)}.</li></ol>
 */
public abstract class ExtendableParcelable implements Parcelable {
    /**
     * Version of this Parcelable. Reader must read only up to the version written.
     * Represents contents version (not class version). For example, original
     * Parcelable (V1) passed to V2-supporting Parcelable will have V1 even if
     * V2-supporting Parcelable has additional member variables added in V2.
     */
    @VersionDef(version = 1)
    public final int version;

    /**
     * Constructor for reading parcel. Call this before reading anything else!
     *
     * @param in The {@link Parcel} to read from.
     * @param version The maximum supported version. The lowest common denominator is used.
     */
    protected ExtendableParcelable(Parcel in, int version) {
        int writerVersion = in.readInt();
        if (version < writerVersion) { // version limited by reader
            this.version = version;
        } else { // version limited by writer
            this.version = writerVersion;
        }
    }

    /**
     * Constructor for writer. Version should be always set.
     */
    protected ExtendableParcelable(int version) {
        this.version = version;
    }

    /**
     * Read header of Parcelable from Parcel. This should be done after super(Parcel, int) and
     * before reading any Parcel. After all reading is done, call
     * {@link #completeReading(Parcel, int)}.
     *
     * @param in the {@link Parcel} to read.
     * @return Last position. This should be passed to {@link #completeReading(Parcel, int)}.
     */
    public int readHeader(Parcel in) {
        int payloadLength = in.readInt();
        int startingPosition = in.dataPosition();
        return startingPosition + payloadLength;
    }

    /**
     * Complete reading and safely discard any unread data.
     * @param in The {@link Parcel} to read.
     * @param lastPosition Last position of this Parcelable in the passed Parcel.
     *                     The value is passed from {@link #readHeader(Parcel)}.
     */
    public void completeReading(Parcel in, int lastPosition) {
        in.setDataPosition(lastPosition);
    }

    /**
     * Write header for writing to Parcel. Do this before writing anything else!
     * Code to use this can look like:
     * <pre class="prettyprint">
     *   int pos = writeHeader(dest);
     *   dest.writeInt(0); // whatever relevant data
     *   ...
     *   completeWrite(dest, pos);</pre>
     * @param dest The {@link Parcel} to write to.
     * @return StartingPosition. Should be passed when calling completeWrite.
     */
    public int writeHeader(Parcel dest) {
        dest.writeInt(version);
        // Temporary value for payload length. Will be replaced in completeWrite.
        dest.writeInt(0);
        // Previous int is 4 bytes before this.
        return dest.dataPosition();
    }

    /**
     * Complete writing the current Parcelable. No more writing to Parcel should be done after
     * this call.
     * @param dest The {@link Parcel} to write to.
     * @param startingPosition StartingPosition returned from writeHeader.
     */
    public void completeWriting(Parcel dest, int startingPosition) {
        int currentPosition = dest.dataPosition();
        dest.setDataPosition(startingPosition - 4);
        int payloadLength = currentPosition - startingPosition;
        dest.writeInt(payloadLength);
        dest.setDataPosition(currentPosition);
    }
}
