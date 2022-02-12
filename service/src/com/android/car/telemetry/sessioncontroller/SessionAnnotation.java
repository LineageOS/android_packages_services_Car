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

package com.android.car.telemetry.sessioncontroller;

import java.util.Objects;

/**
 * {@link SessionAnnotation} is an immutable value class that encapsulates relevant information
 * about session state change event.  Two {@link SessionAnnotation} objects are equal if all their
 * respective public fields are equal by value.
 */
public class SessionAnnotation {
    public final int sessionId;
    public final int sessionState;
    public final long createdAtSinceBootMillis; // Milliseconds since boot.
    public final long createdAtMillis; // Current time in milliseconds - unix time.
    // to capture situations in later analysis when the data might be affected by a sudden reboot
    // due to a crash. Populated from sys.boot.reason property.
    public final String bootReason;

    SessionAnnotation(
            int sessionId,
            @SessionController.SessionControllerState int sessionState,
            long createdAtSinceBootMillis,
            long createdAtMillis,
            String bootReason) {
        this.sessionId = sessionId;
        this.sessionState = sessionState;
        // including deep sleep, similar to SystemClock.elapsedRealtime()
        this.createdAtSinceBootMillis = createdAtSinceBootMillis;
        this.createdAtMillis = createdAtMillis; // unix time
        this.bootReason = bootReason;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, sessionState, createdAtSinceBootMillis, createdAtMillis,
                bootReason);
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("SessionAnnotation{sessionId: ")
                .append(sessionId)
                .append(", sessionState: ")
                .append(sessionState)
                .append(", createdAtSinceBootMillis: ")
                .append(createdAtSinceBootMillis)
                .append(", createdAtMillis: ")
                .append(createdAtMillis)
                .append(", bootReason: ")
                .append(bootReason)
                .append("}")
                .toString();
    }

    /**
     * Two SessionAnnotation objects are equal if all values of its public
     *
     * @param obj the reference object with which to compare.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof SessionAnnotation)) {
            return false;
        }
        SessionAnnotation other = (SessionAnnotation) obj;
        return sessionId == other.sessionId
                && sessionState == other.sessionState
                && createdAtSinceBootMillis == other.createdAtSinceBootMillis
                && createdAtMillis == other.createdAtMillis
                && Objects.equals(bootReason, other.bootReason);
    }
}
