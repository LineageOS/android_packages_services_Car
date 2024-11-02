/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.google.android.car.kitchensink.radio;

import android.annotation.Nullable;
import android.hardware.radio.RadioAlert;
import android.hardware.radio.RadioManager;
import android.util.SparseArray;

import com.android.car.broadcastradio.support.platform.ProgramSelectorExt;

import java.util.Iterator;
import java.util.Map;

final class RadioTestFragmentUtils {

    private RadioTestFragmentUtils() {
        throw new UnsupportedOperationException("RadioTestFragmentUtils class is noninstantiable");
    }

    static String getDabChannelName(RadioManager.ProgramInfo info,
                                    SparseArray<String> dabFrequencyToLabelMap) {
        StringBuilder channelTextBuilder = new StringBuilder();
        channelTextBuilder.append("DAB");

        int dabFrequency = ProgramSelectorExt.getFrequency(info.getSelector());
        if (dabFrequency != ProgramSelectorExt.INVALID_IDENTIFIER_VALUE) {
            channelTextBuilder.append(' ').append(dabFrequencyToLabelMap.get(dabFrequency, ""));
        }

        int dabEnsemble = ProgramSelectorExt.getDabEnsemble(info.getSelector());
        if (dabEnsemble != ProgramSelectorExt.INVALID_IDENTIFIER_VALUE) {
            channelTextBuilder.append(" Ensemble:0x").append(Integer.toHexString(dabEnsemble));
        }
        return channelTextBuilder.toString();
    }

    static SparseArray<String> getDabFrequencyToLabelMap(Map<String, Integer> dabFrequencyTable) {
        SparseArray<String> dabFrequencyToLabelMap = new SparseArray<>();
        if (dabFrequencyTable == null) {
            return dabFrequencyToLabelMap;
        }
        Iterator<Map.Entry<String, Integer>> it = dabFrequencyTable.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Integer> dabFrequencyEntry = it.next();
            dabFrequencyToLabelMap.put(dabFrequencyEntry.getValue(), dabFrequencyEntry.getKey());
        }
        return dabFrequencyToLabelMap;
    }

    @Nullable
    static String alertStatusToString(@RadioAlert.AlertStatus int status) {
        switch (status) {
            case RadioAlert.STATUS_ACTUAL:
                return "ACTUAL";
            case RadioAlert.STATUS_EXERCISE:
                return "EXERCISE";
            case RadioAlert.STATUS_TEST:
                return "TEST";
            default:
                return null;
        }
    }


    @Nullable
    static String alertMessageTypeToString(@RadioAlert.AlertMessageType int messageType) {
        switch (messageType) {
            case RadioAlert.MESSAGE_TYPE_ALERT:
                return "ALERT";
            case RadioAlert.MESSAGE_TYPE_UPDATE:
                return "UPDATE";
            case RadioAlert.MESSAGE_TYPE_CANCEL:
                return "CANCEL";
            default:
                return null;
        }
    }

    @Nullable
    static String alertCategoryToString(@RadioAlert.AlertCategory int category) {
        switch (category) {
            case RadioAlert.CATEGORY_GEO:
                return "GEO";
            case RadioAlert.CATEGORY_MET:
                return "MET";
            case RadioAlert.CATEGORY_SAFETY:
                return "SAFETY";
            case RadioAlert.CATEGORY_SECURITY:
                return "SECURITY";
            case RadioAlert.CATEGORY_RESCUE:
                return "RESCUE";
            case RadioAlert.CATEGORY_FIRE:
                return "FIRE";
            case RadioAlert.CATEGORY_HEALTH:
                return "HEALTH";
            case RadioAlert.CATEGORY_ENV:
                return "ENV";
            case RadioAlert.CATEGORY_TRANSPORT:
                return "TRANSPORT";
            case RadioAlert.CATEGORY_INFRA:
                return "INFRA";
            case RadioAlert.CATEGORY_CBRNE:
                return "CBRNE";
            case RadioAlert.CATEGORY_OTHER:
                return "OTHER";
            default:
                return null;
        }
    }

    @Nullable
    static String alertUrgencyToString(@RadioAlert.AlertUrgency int urgency) {
        switch (urgency) {
            case RadioAlert.URGENCY_IMMEDIATE:
                return "IMMEDIATE";
            case RadioAlert.URGENCY_EXPECTED:
                return "EXPECTED";
            case RadioAlert.URGENCY_FUTURE:
                return "FUTURE";
            case RadioAlert.URGENCY_PAST:
                return "PAST";
            case RadioAlert.URGENCY_UNKNOWN:
                return "UNKNOWN";
            default:
                return null;
        }
    }

    @Nullable
    static String alertSeverityToString(@RadioAlert.AlertSeverity int alertSeverity) {
        switch (alertSeverity) {
            case RadioAlert.SEVERITY_EXTREME:
                return "EXTREME";
            case RadioAlert.SEVERITY_SEVERE:
                return "SEVERE";
            case RadioAlert.SEVERITY_MODERATE:
                return "MODERATE";
            case RadioAlert.SEVERITY_MINOR:
                return "MINOR";
            case RadioAlert.SEVERITY_UNKNOWN:
                return "UNKNOWN";
            default:
                return null;
        }
    }

    @Nullable
    static String alertCertaintyToString(@RadioAlert.AlertCertainty int alertCertainty) {
        switch (alertCertainty) {
            case RadioAlert.CERTAINTY_OBSERVED:
                return "OBSERVED";
            case RadioAlert.CERTAINTY_LIKELY:
                return "LIKELY";
            case RadioAlert.CERTAINTY_POSSIBLE:
                return "POSSIBLE";
            case RadioAlert.CERTAINTY_UNLIKELY:
                return "UNLIKELY";
            case RadioAlert.CERTAINTY_UNKNOWN:
                return "UNKNOWN";
            default:
                return null;
        }
    }
}
