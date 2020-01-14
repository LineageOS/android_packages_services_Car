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
import android.car.media.CarAudioManager;
import android.content.Context;
import android.hardware.automotive.audiocontrol.V1_0.ContextNumber;
import android.text.TextUtils;
import android.util.SparseIntArray;
import android.util.Xml;
import android.view.DisplayAddress;

import com.android.internal.util.Preconditions;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A helper class loads all audio zones from the configuration XML file.
 */
/* package */ class CarAudioZonesHelper {
    private static final int INVALID_ZONE_ID = -1;

    private static final String NAMESPACE = null;
    private static final String TAG_ROOT = "carAudioConfiguration";
    private static final String TAG_AUDIO_ZONES = "zones";
    private static final String TAG_AUDIO_ZONE = "zone";
    private static final String TAG_VOLUME_GROUPS = "volumeGroups";
    private static final String TAG_VOLUME_GROUP = "group";
    private static final String TAG_AUDIO_DEVICE = "device";
    private static final String TAG_CONTEXT = "context";
    private static final String TAG_DISPLAYS = "displays";
    private static final String TAG_DISPLAY = "display";
    private static final String ATTR_VERSION = "version";
    private static final String ATTR_IS_PRIMARY = "isPrimary";
    private static final String ATTR_ZONE_NAME = "name";
    private static final String ATTR_DEVICE_ADDRESS = "address";
    private static final String ATTR_CONTEXT_NAME = "context";
    private static final String ATTR_PHYSICAL_PORT = "port";
    private static final String ATTR_ZONE_ID = "audioZoneId";
    private static final String ATTR_OCCUPANT_ZONE_ID = "occupantZoneId";
    private static final int SUPPORTED_VERSION_1 = 1;
    private static final int SUPPORTED_VERSION_2 = 2;
    private static final Set<Integer> SUPPORTED_VERSIONS;

    private static final Map<String, Integer> CONTEXT_NAME_MAP;

    static {
        CONTEXT_NAME_MAP = new HashMap<>(8);
        CONTEXT_NAME_MAP.put("music", ContextNumber.MUSIC);
        CONTEXT_NAME_MAP.put("navigation", ContextNumber.NAVIGATION);
        CONTEXT_NAME_MAP.put("voice_command", ContextNumber.VOICE_COMMAND);
        CONTEXT_NAME_MAP.put("call_ring", ContextNumber.CALL_RING);
        CONTEXT_NAME_MAP.put("call", ContextNumber.CALL);
        CONTEXT_NAME_MAP.put("alarm", ContextNumber.ALARM);
        CONTEXT_NAME_MAP.put("notification", ContextNumber.NOTIFICATION);
        CONTEXT_NAME_MAP.put("system_sound", ContextNumber.SYSTEM_SOUND);

        SUPPORTED_VERSIONS = new HashSet<>(2);
        SUPPORTED_VERSIONS.add(SUPPORTED_VERSION_1);
        SUPPORTED_VERSIONS.add(SUPPORTED_VERSION_2);
    }

    private final Context mContext;
    private final Map<String, CarAudioDeviceInfo> mAddressToCarAudioDeviceInfo;
    private final InputStream mInputStream;
    private final Set<Long> mPortIds;
    private final SparseIntArray mZoneIdToOccupantZoneIdMapping;
    private final Set<Integer> mAudioZoneIds;

    private boolean mHasPrimaryZone;
    private int mNextSecondaryZoneId;
    private int mCurrentVersion;

    /**
     *  <p><b>Note: <b/> CarAudioZonesHelper is expected to be used
     *  from a single thread. This should be the same thread that
     *  originally called new CarAudioZonesHelper.<p/>
     *
     */
    CarAudioZonesHelper(Context context, @NonNull InputStream inputStream,
            @NonNull List<CarAudioDeviceInfo> carAudioDeviceInfos) {
        mContext = context;
        mInputStream = inputStream;
        mAddressToCarAudioDeviceInfo = CarAudioZonesHelper.generateAddressToInfoMap(
                carAudioDeviceInfos);

        mNextSecondaryZoneId = CarAudioManager.PRIMARY_AUDIO_ZONE + 1;
        mPortIds = new HashSet<>();
        mZoneIdToOccupantZoneIdMapping = new SparseIntArray();
        mAudioZoneIds = new HashSet<>();
    }

    SparseIntArray getCarAudioZoneIdToOccupantZoneIdMapping() {
        return mZoneIdToOccupantZoneIdMapping;
    }

    // TODO: refactor this method to return List<CarAudioZone>
    CarAudioZone[] loadAudioZones() throws IOException, XmlPullParserException {
        List<CarAudioZone> carAudioZones = new ArrayList<>();
        parseCarAudioZones(carAudioZones, mInputStream);
        return carAudioZones.toArray(new CarAudioZone[0]);
    }

    private static Map<String, CarAudioDeviceInfo> generateAddressToInfoMap(
            List<CarAudioDeviceInfo> carAudioDeviceInfos) {
        return carAudioDeviceInfos.stream()
                .filter(info -> !TextUtils.isEmpty(info.getAddress()))
                .collect(Collectors.toMap(CarAudioDeviceInfo::getAddress, info -> info));
    }

    private void parseCarAudioZones(List<CarAudioZone> carAudioZones, InputStream stream)
            throws XmlPullParserException, IOException {
        final XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, NAMESPACE != null);
        parser.setInput(stream, null);

        // Ensure <carAudioConfiguration> is the root
        parser.nextTag();
        parser.require(XmlPullParser.START_TAG, NAMESPACE, TAG_ROOT);

        // Version check
        final int versionNumber = Integer.parseInt(
                parser.getAttributeValue(NAMESPACE, ATTR_VERSION));
        if (!SUPPORTED_VERSIONS.contains(versionNumber)) {
            throw new IllegalArgumentException("Latest Supported version:"
                    + SUPPORTED_VERSION_2 + " , got version:" + versionNumber);
        }

        mCurrentVersion = versionNumber;

        // Get all zones configured under <zones> tag
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_AUDIO_ZONES.equals(parser.getName())) {
                parseAudioZones(parser, carAudioZones);
            } else {
                skip(parser);
            }
        }
    }

    private void parseAudioZones(XmlPullParser parser, List<CarAudioZone> carAudioZones)
            throws XmlPullParserException, IOException {
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_AUDIO_ZONE.equals(parser.getName())) {
                carAudioZones.add(parseAudioZone(parser));
            } else {
                skip(parser);
            }
        }
        Preconditions.checkArgument(mHasPrimaryZone, "Requires one primary zone");
        carAudioZones.sort(Comparator.comparing(CarAudioZone::getId));
    }

    private CarAudioZone parseAudioZone(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        final boolean isPrimary = Boolean.parseBoolean(
                parser.getAttributeValue(NAMESPACE, ATTR_IS_PRIMARY));
        if (isPrimary) {
            Preconditions.checkArgument(!mHasPrimaryZone, "Only one primary zone is allowed");
            mHasPrimaryZone = true;
        }
        final String zoneName = parser.getAttributeValue(NAMESPACE, ATTR_ZONE_NAME);
        final int audioZoneId = getZoneId(isPrimary, parser);
        parseOccupantZoneId(audioZoneId, parser);
        final CarAudioZone zone = new CarAudioZone(audioZoneId, zoneName);
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            // Expect one <volumeGroups> in one audio zone
            if (TAG_VOLUME_GROUPS.equals(parser.getName())) {
                parseVolumeGroups(parser, zone);
            } else if (TAG_DISPLAYS.equals(parser.getName())) {
                parseDisplays(parser, zone);
            } else {
                skip(parser);
            }
        }
        return zone;
    }

    private int getZoneId(boolean isPrimary, XmlPullParser parser) {
        String audioZoneIdString = parser.getAttributeValue(NAMESPACE, ATTR_ZONE_ID);
        if (mCurrentVersion == SUPPORTED_VERSION_1) {
            Preconditions.checkArgument(audioZoneIdString == null,
                    "Invalid audio attribute %s"
                            + ", Please update car audio configurations file "
                            + "to version to 2 to use it.", ATTR_ZONE_ID);
            return isPrimary ? CarAudioManager.PRIMARY_AUDIO_ZONE
                    : getNextSecondaryZoneId();
        }
        // Primary zone does not need to define it
        if (isPrimary && audioZoneIdString == null) {
            return CarAudioManager.PRIMARY_AUDIO_ZONE;
        }
        Objects.requireNonNull(audioZoneIdString, () ->
                "Requires " + ATTR_ZONE_ID + " for all audio zones.");
        int zoneId = parsePositiveIntAttribute(ATTR_ZONE_ID, audioZoneIdString);
        //Verify that primary zone id is PRIMARY_AUDIO_ZONE
        if (isPrimary) {
            Preconditions.checkArgument(zoneId == CarAudioManager.PRIMARY_AUDIO_ZONE,
                    "Primary zone %s must be %d or it can be left empty.",
                    ATTR_ZONE_ID, CarAudioManager.PRIMARY_AUDIO_ZONE);
        } else {
            Preconditions.checkArgument(zoneId != CarAudioManager.PRIMARY_AUDIO_ZONE,
                    "%s can only be %d for primary zone.",
                    ATTR_ZONE_ID, CarAudioManager.PRIMARY_AUDIO_ZONE);
        }
        validateAudioZoneIdIsUnique(zoneId);
        return zoneId;
    }

    private void parseOccupantZoneId(int audioZoneId, XmlPullParser parser) {
        String occupantZoneIdString = parser.getAttributeValue(NAMESPACE, ATTR_OCCUPANT_ZONE_ID);
        if (mCurrentVersion == SUPPORTED_VERSION_1) {
            Preconditions.checkArgument(occupantZoneIdString == null,
                    "Invalid audio attribute %s"
                            + ", Please update car audio configurations file "
                            + "to version to 2 to use it.", ATTR_OCCUPANT_ZONE_ID);
            return;
        }
        //Occupant id not required for all zones
        if (occupantZoneIdString == null) {
            return;
        }
        int occupantZoneId = parsePositiveIntAttribute(ATTR_OCCUPANT_ZONE_ID, occupantZoneIdString);
        validateOccupantZoneIdIsUnique(occupantZoneId);
        mZoneIdToOccupantZoneIdMapping.put(audioZoneId, occupantZoneId);
    }

    private int parsePositiveIntAttribute(String attribute, String integerString) {
        try {
            return Integer.parseUnsignedInt(integerString);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException(attribute + " must be a positive integer, but was \""
                    + integerString + "\" instead." , e);
        }
    }

    private void parseDisplays(XmlPullParser parser, CarAudioZone zone)
            throws IOException, XmlPullParserException {
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_DISPLAY.equals(parser.getName())) {
                zone.addPhysicalDisplayAddress(parsePhysicalDisplayAddress(parser));
            }
            skip(parser);
        }
    }

    private DisplayAddress.Physical parsePhysicalDisplayAddress(XmlPullParser parser) {
        String port = parser.getAttributeValue(NAMESPACE, ATTR_PHYSICAL_PORT);
        long portId;
        try {
            portId = Long.parseLong(port);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Port " +  port + " is not a number", e);
        }
        validatePortIsUnique(portId);
        return DisplayAddress.fromPhysicalDisplayId(portId);
    }

    private void validatePortIsUnique(Long portId) {
        if (mPortIds.contains(portId)) {
            throw new RuntimeException("Port Id " + portId + " is already associated with a zone");
        }
        mPortIds.add(portId);
    }

    private void validateOccupantZoneIdIsUnique(int occupantZoneId) {
        if (mZoneIdToOccupantZoneIdMapping.indexOfValue(occupantZoneId) > -1) {
            throw new IllegalArgumentException(ATTR_OCCUPANT_ZONE_ID + " " + occupantZoneId
                    + " is already associated with a zone");
        }
    }

    private void validateAudioZoneIdIsUnique(int audioZoneId) {
        if (mAudioZoneIds.contains(audioZoneId)) {
            throw new IllegalArgumentException(ATTR_ZONE_ID + " " + audioZoneId
                    + " is already associated with a zone");
        }
        mAudioZoneIds.add(audioZoneId);
    }

    private void parseVolumeGroups(XmlPullParser parser, CarAudioZone zone)
            throws XmlPullParserException, IOException {
        int groupId = 0;
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_VOLUME_GROUP.equals(parser.getName())) {
                zone.addVolumeGroup(parseVolumeGroup(parser, zone.getId(), groupId));
                groupId++;
            } else {
                skip(parser);
            }
        }
    }

    private CarVolumeGroup parseVolumeGroup(XmlPullParser parser, int zoneId, int groupId)
            throws XmlPullParserException, IOException {
        final CarVolumeSettings settings = new CarVolumeSettings(mContext);
        final CarVolumeGroup group = new CarVolumeGroup(settings, zoneId, groupId);
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_AUDIO_DEVICE.equals(parser.getName())) {
                String address = parser.getAttributeValue(NAMESPACE, ATTR_DEVICE_ADDRESS);
                parseVolumeGroupContexts(parser, group, address);
            } else {
                skip(parser);
            }
        }
        return group;
    }

    private void parseVolumeGroupContexts(
            XmlPullParser parser, CarVolumeGroup group, String address)
            throws XmlPullParserException, IOException {
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_CONTEXT.equals(parser.getName())) {
                group.bind(
                        parseContextNumber(parser.getAttributeValue(NAMESPACE, ATTR_CONTEXT_NAME)),
                        mAddressToCarAudioDeviceInfo.get(address));
            }
            // Always skip to upper level since we're at the lowest.
            skip(parser);
        }
    }

    private void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        if (parser.getEventType() != XmlPullParser.START_TAG) {
            throw new IllegalStateException();
        }
        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
            }
        }
    }

    private int parseContextNumber(String context) {
        return CONTEXT_NAME_MAP.getOrDefault(context.toLowerCase(), ContextNumber.INVALID);
    }

    private int getNextSecondaryZoneId() {
        int zoneId = mNextSecondaryZoneId;
        mNextSecondaryZoneId += 1;
        return zoneId;
    }
}
