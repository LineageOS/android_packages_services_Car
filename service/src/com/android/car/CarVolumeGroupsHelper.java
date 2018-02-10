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
package com.android.car;

import android.annotation.XmlRes;
import android.car.media.CarVolumeGroup;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/* package */ class CarVolumeGroupsHelper {

    private static final String TAG_VOLUME_GROUPS = "volumeGroups";
    private static final String TAG_GROUP = "group";
    private static final String TAG_CONTEXT = "context";

    private final Resources mResources;
    private final @XmlRes int mXmlConfiguration;

    CarVolumeGroupsHelper(Context context, @XmlRes int xmlConfiguration) {
        mResources = context.getResources();
        mXmlConfiguration = xmlConfiguration;
    }

    CarVolumeGroup[] loadVolumeGroups() {
        List<CarVolumeGroup> carVolumeGroups = new ArrayList<>();
        try (XmlResourceParser parser = mResources.getXml(mXmlConfiguration)) {
            AttributeSet attrs = Xml.asAttributeSet(parser);
            int type;
            // Traverse to the first start tag
            while ((type=parser.next()) != XmlResourceParser.END_DOCUMENT
                    && type != XmlResourceParser.START_TAG) {
            }

            if (!TAG_VOLUME_GROUPS.equals(parser.getName())) {
                throw new RuntimeException("Meta-data does not start with volumeGroups tag");
            }
            int outerDepth = parser.getDepth();
            while ((type=parser.next()) != XmlResourceParser.END_DOCUMENT
                    && (type != XmlResourceParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlResourceParser.END_TAG) {
                    continue;
                }
                if (TAG_GROUP.equals(parser.getName())) {
                    carVolumeGroups.add(parseVolumeGroup(attrs, parser));
                }
            }
        } catch (Exception e) {
            Log.e(CarLog.TAG_AUDIO, "Error parsing volume groups configuration", e);
        }
        return carVolumeGroups.toArray(new CarVolumeGroup[carVolumeGroups.size()]);
    }

    private CarVolumeGroup parseVolumeGroup(AttributeSet attrs, XmlResourceParser parser)
            throws XmlPullParserException, IOException {
        int type;
        TypedArray a = mResources.obtainAttributes(attrs, R.styleable.volumeGroups_group);
        String title = a.getString(R.styleable.volumeGroups_group_name);
        a.recycle();

        List<Integer> contexts = new ArrayList<>();
        int innerDepth = parser.getDepth();
        while ((type=parser.next()) != XmlResourceParser.END_DOCUMENT
                && (type != XmlResourceParser.END_TAG || parser.getDepth() > innerDepth)) {
            if (type == XmlResourceParser.END_TAG) {
                continue;
            }
            if (TAG_CONTEXT.equals(parser.getName())) {
                TypedArray c = mResources.obtainAttributes(
                        attrs, R.styleable.volumeGroups_context);
                contexts.add(c.getInt(R.styleable.volumeGroups_context_context, -1));
                c.recycle();
            }
        }

        return new CarVolumeGroup(title,
                contexts.stream().mapToInt(i -> i).filter(i -> i >= 0).toArray());
    }
}
