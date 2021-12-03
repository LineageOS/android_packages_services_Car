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

package android.car.builtin.util;

import android.annotation.SystemApi;
import android.util.EventLog;

/**
 * Helper for {@link EventLog}
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class EventLogHelper {

    public static void writeCarServiceInit(int numberServices) {
        EventLog.writeEvent(EventLogTags.CAR_SERVICE_INIT, numberServices);
    }

    public static void writeCarServiceVhalReconnected(int numberServices) {
        EventLog.writeEvent(EventLogTags.CAR_SERVICE_VHAL_RECONNECTED, numberServices);
    }

    public static void writeCarServiceSetCarServiceHelper(int pid) {
        EventLog.writeEvent(EventLogTags.CAR_SERVICE_SET_CAR_SERVICE_HELPER, pid);
    }

    public static void writeCarServiceOnUserLifecycle(int type, int fromUserId, int toUserId) {
        EventLog.writeEvent(EventLogTags.CAR_SERVICE_ON_USER_LIFECYCLE, type, fromUserId, toUserId);
    }

    public static void writeCarServiceCreate(boolean hasVhal) {
        EventLog.writeEvent(EventLogTags.CAR_SERVICE_CREATE, hasVhal ? 1 : 0);
    }

    public static void writeCarServiceConnected(String interfaceName) {
        EventLog.writeEvent(EventLogTags.CAR_SERVICE_CONNECTED, interfaceName);
    }

    public static void writeCarServiceDestroy(boolean hasVhal) {
        EventLog.writeEvent(EventLogTags.CAR_SERVICE_DESTROY, hasVhal ? 1 : 0);
    }

    public static void writeCarServiceVhalDied(long cookie) {
        EventLog.writeEvent(EventLogTags.CAR_SERVICE_VHAL_DIED, cookie);
    }

    public static void writeCarServiceInitBootUser() {
        EventLog.writeEvent(EventLogTags.CAR_SERVICE_INIT_BOOT_USER);
    }

    public static void writeCarServiceOnUserRemoved(int userId) {
        EventLog.writeEvent(EventLogTags.CAR_SERVICE_ON_USER_REMOVED, userId);
    }

    private EventLogHelper() {
        throw new UnsupportedOperationException();
    }
}
