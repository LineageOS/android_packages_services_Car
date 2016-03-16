/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.car;

import android.annotation.SystemApi;

/**
 * Collection of utilities for handling zones
 * @hide
 */
@SystemApi
public class VehicleZoneUtil {

    /**
     * Change zone flag into index with available zones.
     * For example with zoned of 0x80000001, 0x1 will be index 0 and 0x80000000 will be index 2.
     * @param available zones
     * @param zone flag for the zone to get index
     * @return 0 if zones is 0 or zone is 0.
     */
    public static int zoneToIndex(int zones, int zone) {
        if (zone == 0 || zones == 0) {
            return 0;
        }
        int flag = 0x1;
        int index = 0;
        for (int i = 0; i < 32; i++) {
            if ((flag & zones) != 0) {
                if ((flag & zone) != 0) {
                    return index;
                }
                index++;
            }
            flag <<= 1;
        }
        return 0;
    }

    /**
     * Return number of zones (non-zero flag) from given zones.
     */
    public static int getNumBerOfZones(int zones) {
        if (zones == 0) {
            return 0;
        }
        int numZones = 0;
        int flag = 0x1;
        for (int i = 0; i < 32; i++) {
            if ((flag & zones) != 0) {
                numZones++;
            }
            flag <<= 1;
        }
        return numZones;
    }

    /**
     * Return bit flag of first zone. If zones is 0, it will just return 0.
     */
    public static int getFirstZone(int zones) {
        if (zones == 0) {
            return 0;
        }
        int flag = 0x1;
        for (int i = 0; i < 32; i++) {
            if ((flag & zones) != 0) {
                return flag;
            }
            flag <<= 1;
        }
        return 0;
    }

    /**
     * Return bit flag of zone available after startingZone. For zones of 0x7 with startingZone of
     * 0x2, it will return 0x4. If no zone exist after startingZone, it will return 0.
     */
    public static int getNextZone(int zones, int startingZone) {
        int flag = getFirstZone(startingZone) << 1;
        if (flag == 0) {
            return 0;
        }
        while (flag != 0x80000000) {
            if ((flag & zones) != 0) {
                return flag;
            }
            flag <<= 1;
        }
        if ((flag & zones) != 0) {
            return flag;
        }
        return 0;
    }

    /**
     * Return array of zone with each active zone in one index. This can be useful for iterating
     * all available zones.
     */
    public static int[] listAllZones(int zones) {
        int numberOfZones = getNumBerOfZones(zones);
        int[] list = new int[numberOfZones];
        if (numberOfZones == 0) {
            return list;
        }
        int flag = 0x1;
        int arrayIndex = 0;
        for (int i = 0; i < 32; i++) {
            if ((flag & zones) != 0) {
                list[arrayIndex] = flag;
                arrayIndex++;
            }
            flag <<= 1;
        }
        return list;
    }
}
