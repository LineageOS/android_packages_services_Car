/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.car.audio;

import android.annotation.NonNull;
import android.annotation.XmlRes;
import android.car.media.CarAudioManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;

import com.android.car.CarLog;
import com.android.car.R;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A helper class loads all audio zones from the configuration XML file.
 */
/* package */ class CarAudioZonesHelper implements CarAudioService.CarAudioZonesLoader {

    private static final String TAG_ROOT = "carAudioConfiguration";
    private static final String TAG_AUDIO_ZONES = "zones";
    private static final String TAG_AUDIO_ZONE = "zone";
    private static final String TAG_VOLUME_GROUPS = "volumeGroups";
    private static final String TAG_VOLUME_GROUP = "group";
    private static final String TAG_AUDIO_DEVICE = "device";
    private static final String TAG_CONTEXT = "context";
    private static final int SUPPORTED_VERSION = 1;

    private final Context mContext;
    private final int mXmlConfiguration;
    private final SparseArray<CarAudioDeviceInfo> mBusToCarAudioDeviceInfo;

    private int mNextSecondaryZoneId;

    CarAudioZonesHelper(Context context, @XmlRes int xmlConfiguration,
            @NonNull SparseArray<CarAudioDeviceInfo> busToCarAudioDeviceInfo) {
        mContext = context;
        mXmlConfiguration = xmlConfiguration;
        mBusToCarAudioDeviceInfo = busToCarAudioDeviceInfo;

        mNextSecondaryZoneId = CarAudioManager.PRIMARY_AUDIO_ZONE + 1;
    }

    @Override
    public CarAudioZone[] loadAudioZones() {
        List<CarAudioZone> carAudioZones = new ArrayList<>();
        try (XmlResourceParser parser = mContext.getResources().getXml(mXmlConfiguration)) {
            AttributeSet attrs = Xml.asAttributeSet(parser);
            int type;
            // Traverse to the first start tag, <carAudioConfiguration> in this case
            while ((type = parser.next()) != XmlResourceParser.END_DOCUMENT
                    && type != XmlResourceParser.START_TAG) {
                // ignored
            }
            if (!TAG_ROOT.equals(parser.getName())) {
                throw new RuntimeException("Meta-data does not start with " + TAG_ROOT);
            }

            // Version check
            TypedArray c = mContext.getResources().obtainAttributes(
                    attrs, R.styleable.carAudioConfiguration);
            final int versionNumber = c.getInt(R.styleable.carAudioConfiguration_version, -1);
            if (versionNumber != SUPPORTED_VERSION) {
                throw new RuntimeException("Support version:"
                        + SUPPORTED_VERSION + " only, got version:" + versionNumber);
            }
            c.recycle();

            // And follows with the <zones> tag
            while ((type = parser.next()) != XmlResourceParser.END_DOCUMENT
                    && type != XmlResourceParser.START_TAG) {
                // ignored
            }
            if (!TAG_AUDIO_ZONES.equals(parser.getName())) {
                throw new RuntimeException("Configuration should begin with a <zones> tag");
            }
            int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlResourceParser.END_DOCUMENT
                    && (type != XmlResourceParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlResourceParser.END_TAG) {
                    continue;
                }
                if (TAG_AUDIO_ZONE.equals(parser.getName())) {
                    carAudioZones.add(parseAudioZone(attrs, parser));
                }
            }
        } catch (Exception e) {
            Log.e(CarLog.TAG_AUDIO, "Error parsing unified car audio configuration", e);

        }
        return carAudioZones.toArray(new CarAudioZone[0]);
    }

    private CarAudioZone parseAudioZone(AttributeSet attrs, XmlResourceParser parser)
            throws XmlPullParserException, IOException {
        TypedArray c = mContext.getResources().obtainAttributes(
                attrs, R.styleable.carAudioConfiguration);
        final boolean isPrimary = c.getBoolean(R.styleable.carAudioConfiguration_isPrimary, false);
        final String zoneName = c.getString(R.styleable.carAudioConfiguration_name);
        c.recycle();

        CarAudioZone zone = new CarAudioZone(
                isPrimary ? CarAudioManager.PRIMARY_AUDIO_ZONE : getNextSecondaryZoneId(),
                zoneName);
        int type;
        // Traverse to the first start tag, <volumeGroups> in this case
        while ((type = parser.next()) != XmlResourceParser.END_DOCUMENT
                && type != XmlResourceParser.START_TAG) {
            // ignored
        }

        if (!TAG_VOLUME_GROUPS.equals(parser.getName())) {
            throw new RuntimeException("Audio zone does not start with <volumeGroups> tag");
        }
        int outerDepth = parser.getDepth();
        int groupId = 0;
        while ((type = parser.next()) != XmlResourceParser.END_DOCUMENT
                && (type != XmlResourceParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlResourceParser.END_TAG) {
                continue;
            }
            if (TAG_VOLUME_GROUP.equals(parser.getName())) {
                zone.addVolumeGroup(parseVolumeGroup(zone.getId(), groupId, attrs, parser));
                groupId += 1;
            }
        }
        return zone;
    }

    private CarVolumeGroup parseVolumeGroup(
            int zoneId, int groupId, AttributeSet attrs, XmlResourceParser parser)
            throws XmlPullParserException, IOException {
        final CarVolumeGroup group = new CarVolumeGroup(mContext, zoneId, groupId);
        int type;
        int outerDepth = parser.getDepth();
        while ((type = parser.next()) != XmlResourceParser.END_DOCUMENT
                && (type != XmlResourceParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlResourceParser.END_TAG) {
                continue;
            }
            if (TAG_AUDIO_DEVICE.equals(parser.getName())) {
                TypedArray c = mContext.getResources().obtainAttributes(
                        attrs, R.styleable.carAudioConfiguration);
                final String address = c.getString(R.styleable.carAudioConfiguration_address);
                parseVolumeGroupContexts(group,
                        CarAudioDeviceInfo.parseDeviceAddress(address), attrs, parser);
                c.recycle();
            }
        }
        return group;
    }

    private void parseVolumeGroupContexts(
            CarVolumeGroup group, int busNumber, AttributeSet attrs, XmlResourceParser parser)
            throws XmlPullParserException, IOException {
        int type;
        int innerDepth = parser.getDepth();
        while ((type = parser.next()) != XmlResourceParser.END_DOCUMENT
                && (type != XmlResourceParser.END_TAG || parser.getDepth() > innerDepth)) {
            if (type == XmlResourceParser.END_TAG) {
                continue;
            }
            if (TAG_CONTEXT.equals(parser.getName())) {
                TypedArray c = mContext.getResources().obtainAttributes(
                        attrs, R.styleable.volumeGroups_context);
                final int contextNumber = c.getInt(
                        R.styleable.volumeGroups_context_context, -1);
                c.recycle();
                group.bind(contextNumber, busNumber, mBusToCarAudioDeviceInfo.get(busNumber));
            }
        }
    }

    private int getNextSecondaryZoneId() {
        int zoneId = mNextSecondaryZoneId;
        mNextSecondaryZoneId += 1;
        return zoneId;
    }
}
