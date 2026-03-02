/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;
import static android.media.AudioAttributes.USAGE_CALL_ASSISTANT;
import static android.media.AudioAttributes.USAGE_EMERGENCY;
import static android.media.AudioAttributes.USAGE_SAFETY;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION;
import static android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.Nullable;
import android.car.builtin.media.AudioManagerHelper;
import android.car.builtin.os.TraceHelper;
import android.car.builtin.util.Slogf;
import android.car.media.EnforcedAudioFocusInfo;
import android.car.media.IEnforceableAudioFocusCallback;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioPlaybackConfiguration;
import android.media.PlayerProxy;
import android.os.Binder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.Trace;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.DebugUtils;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.car.internal.util.LocalLog;
import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Class to manage audio focus enforcement in cars
 */
final class CarAudioFocusEnforcement {

    private static final String TAG = CarLog.TAG_AUDIO;
    private static final int FOCUS_ENFORCEMENT_LOGGER_QUEUE_SIZE = 50;

    private final LocalLog mFocusEnforcementLogger =
            new LocalLog(FOCUS_ENFORCEMENT_LOGGER_QUEUE_SIZE);
    private final RemoteCallbackList<IEnforceableAudioFocusCallback>
            mFocusEnforcementCallbacks = new RemoteCallbackList<>();

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final ArraySet<AudioAttributes> mEnforceableAttributes = new ArraySet<>();
    @GuardedBy("mLock")
    private final ArraySet<AudioAttributes> mDoNotSilenceAttributes = new ArraySet<>();
    @GuardedBy("mLock")
    private List<AudioFocusInfo> mPrimaryZoneFocusHolders = new CopyOnWriteArrayList<>();
    @GuardedBy("mLock")
    private List<AudioPlaybackConfiguration> mPrimaryZoneActivePlaybacks =
            new CopyOnWriteArrayList<>();
    @GuardedBy("mLock")
    private final ArraySet<Integer> mCurrentlySilencedPlayerIDs = new ArraySet<>();
    @GuardedBy("mLock")
    private boolean mRelaxedParkModeEnabled;
    @GuardedBy("mLock")
    private boolean mHasCriticalAudioFocusRequest;

    private static boolean isCriticalAudioUsage(int usage) {
        return usage == USAGE_EMERGENCY || usage == USAGE_SAFETY || usage == USAGE_CALL_ASSISTANT
                || usage == USAGE_VOICE_COMMUNICATION
                || usage == USAGE_VOICE_COMMUNICATION_SIGNALLING;
    }

    CarAudioFocusEnforcement() {
    }

    void setEnforceableAttributes(List<AudioAttributes> enforceableAttributes) {
        Objects.requireNonNull(enforceableAttributes,
                "Enforceable audio attributes can not be null");
        synchronized (mLock) {
            mEnforceableAttributes.clear();
            for (int c = 0; c < enforceableAttributes.size(); c++) {
                var audioAttribute = enforceableAttributes.get(c);
                if (isCriticalAudioUsage(audioAttribute.getSystemUsage())) {
                    throw new IllegalArgumentException(
                            "Car Audio focus enforcement does not support usage "
                                    + DebugUtils.constantToString(AudioAttributes.class,
                                    "USAGE_", audioAttribute.getSystemUsage()));
                }
                if (!mEnforceableAttributes.add(audioAttribute)) {
                    Slogf.w(CarLog.TAG_AUDIO, "Silence audio attribute %s repeats",
                            audioAttribute);
                }
            }
        }
    }

    List<AudioAttributes> getEnforceableAttributes() {
        synchronized (mLock) {
            return new CopyOnWriteArrayList<>(mEnforceableAttributes);
        }
    }

    void setDoNotSilenceAttributes(List<AudioAttributes> doNotSilenceAttributes) {
        Objects.requireNonNull(doNotSilenceAttributes,
                "Do not silence audio attributes can not be null");
        synchronized (mLock) {
            mDoNotSilenceAttributes.clear();
            for (int c = 0; c < doNotSilenceAttributes.size(); c++) {
                var audioAttribute = doNotSilenceAttributes.get(c);
                if (!mDoNotSilenceAttributes.add(audioAttribute)) {
                    Slogf.w(CarLog.TAG_AUDIO, "Do not silence audio attribute %s repeats",
                            audioAttribute);
                }
            }
        }
    }

    void release() {
        mFocusEnforcementCallbacks.kill();
    }

    boolean registerCallback(IEnforceableAudioFocusCallback callback) {
        if (callback == null) {
            Slogf.w(TAG, "Enforceable audio focus callback can not be null");
            return false;
        }
        var pid = Binder.getCallingPid();
        var uid = Binder.getCallingUid();
        var callingIdentity = new CallbackIdentity(pid, uid);
        return mFocusEnforcementCallbacks.register(callback, callingIdentity);
    }

    boolean unregisterCallback(IEnforceableAudioFocusCallback callback) {
        if (callback == null) {
            Slogf.w(TAG, "Enforceable audio focus callback can not be null");
            return false;
        }
        return mFocusEnforcementCallbacks.unregister(callback);
    }

    List<AudioAttributes> getDoNotSilenceAttributes() {
        synchronized (mLock) {
            return new CopyOnWriteArrayList<>(mDoNotSilenceAttributes);
        }
    }

    private void evaluateAndEnforceFocusState(
            List<AudioPlaybackConfiguration> activePlaybacks,
            List<AudioFocusInfo> focusHolders, boolean relaxedParkModeEnabled,
            boolean hasCriticalAudioFocusRequest) {
        Trace.traceBegin(TraceHelper.TRACE_TAG_CAR_SERVICE,
                "CarAudioFocusEnforcement.evaluateAndEnforceFocusState");
        try {
            List<EnforcedAudioFocusInfo> enforcedAudioFocusInfos = new ArrayList<>();
            if (relaxedParkModeEnabled && !hasCriticalAudioFocusRequest) {
                List<AudioPlaybackConfiguration> playbacksToUnsilence = new ArrayList<>();
                synchronized (mLock) {
                    for (int c = 0; c < activePlaybacks.size(); c++) {
                        var playback = activePlaybacks.get(c);
                        if (mCurrentlySilencedPlayerIDs.contains(playback.getPlayerInterfaceId())) {
                            playbacksToUnsilence.add(playback);
                        }
                    }
                }
                for (int i = 0; i < playbacksToUnsilence.size(); i++) {
                    var info = enforceAudioFocus(playbacksToUnsilence.get(i),
                            /* silence= */ false);
                    if (info != null) {
                        enforcedAudioFocusInfos.add(info);
                    }
                }
                handleInformCallbacks(enforcedAudioFocusInfos);
                return;
            }

            List<AudioAttributes> enforceableAttributes;
            List<AudioAttributes> doNotSilenceAttributes;
            synchronized (mLock) {
                enforceableAttributes = new ArrayList<>(mEnforceableAttributes);
                doNotSilenceAttributes = new ArrayList<>(mDoNotSilenceAttributes);
            }

            for (int c = 0; c < activePlaybacks.size(); c++) {
                var playback = activePlaybacks.get(c);
                // Skip unmanaged playbacks
                if (!audioPlaybackCanBeSilenced(playback, enforceableAttributes)) {
                    continue;
                }
                boolean silence = shouldSilence(playback, focusHolders, doNotSilenceAttributes);
                var info = enforceAudioFocus(playback, silence);
                if (info != null) {
                    enforcedAudioFocusInfos.add(info);
                }
            }
            handleInformCallbacks(enforcedAudioFocusInfos);
        } finally {
            Trace.traceEnd(TraceHelper.TRACE_TAG_CAR_SERVICE);
        }
    }

    private boolean audioPlaybackCanBeSilenced(AudioPlaybackConfiguration playback,
            List<AudioAttributes> enforceableAttributes) {
        return playback != null && audioAttributesCanBeSilenced(playback.getAudioAttributes(),
                enforceableAttributes);
    }

    private boolean audioAttributesCanBeSilenced(AudioAttributes attributes,
            List<AudioAttributes> enforceableAttributes) {
        if (attributes == null) {
            return false;
        }
        for (int i = 0; i < enforceableAttributes.size(); i++) {
            AudioAttributes enforceableAttribute = enforceableAttributes.get(i);
            if (CarAudioContext.getAudioAttributesMatchScore(attributes,
                    enforceableAttribute) >= CarAudioContext.ATTR_MATCH_SCORE_USAGE) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private EnforcedAudioFocusInfo enforceAudioFocus(AudioPlaybackConfiguration playback,
            boolean silence) {
        Trace.traceBegin(TraceHelper.TRACE_TAG_CAR_SERVICE,
                "CarAudioFocusEnforcement.enforceAudioFocus-" + playback.getPlayerInterfaceId());
        try {
            String silenceMessage = silence ? "silence" : "unsilence";
            PlayerProxy proxy = playback.getPlayerProxy();
            if (proxy == null) {
                var message = "Could not enforce " + silenceMessage + " for "
                        + playback + " proxy player missing";
                Slogf.e(TAG, message);
                mFocusEnforcementLogger.log(message);
                return null;
            }

            synchronized (mLock) {
                boolean isCurrentlySilenced = mCurrentlySilencedPlayerIDs.contains(
                        playback.getPlayerInterfaceId());
                boolean shouldAct = silence != isCurrentlySilenced;

                if (!shouldAct) {
                    String info = "Should " + silenceMessage  + " player "
                            + playback.getPlayerInterfaceId() + " but "
                            + (isCurrentlySilenced ? "already silenced" : "not silenced")
                            + " by car focus enforcement";
                    Slogf.i(TAG, info);
                    return null;
                }

                if (silence) {
                    mCurrentlySilencedPlayerIDs.add(playback.getPlayerInterfaceId());
                } else {
                    mCurrentlySilencedPlayerIDs.remove(playback.getPlayerInterfaceId());
                }
            }

            var message = "Enforced " + silenceMessage + " for " + playback;
            try {
                Trace.traceBegin(TraceHelper.TRACE_TAG_CAR_SERVICE,
                        "CarAudioFocusEnforcement.enforceAudioFocus.setVolume-"
                        + playback.getPlayerInterfaceId());
                if (silence) {
                    proxy.setVolume(0.0f);
                } else {
                    proxy.setVolume(1.0f);
                }
            } catch (Exception e) {
                message = "Failed to enforce audio focus for " + playback + " with exception "
                        + e.getMessage();
                Slogf.e(CarLog.TAG_AUDIO, message, e);
                // Revert the state if the binder call fails
                synchronized (mLock) {
                    if (silence) {
                        mCurrentlySilencedPlayerIDs.remove(playback.getPlayerInterfaceId());
                    } else {
                        mCurrentlySilencedPlayerIDs.add(playback.getPlayerInterfaceId());
                    }
                }
                return null;
            } finally {
                Trace.traceEnd(TraceHelper.TRACE_TAG_CAR_SERVICE);
            }
            mFocusEnforcementLogger.log(message);
            return new EnforcedAudioFocusInfo(playback.getClientUid(),
                    playback.getAudioAttributes(), silence);
        } finally {
            Trace.traceEnd(TraceHelper.TRACE_TAG_CAR_SERVICE);
        }
    }

    private boolean shouldSilence(AudioPlaybackConfiguration playback, List<AudioFocusInfo> infos,
            List<AudioAttributes> doNotSilenceAttributes) {
        for (int c = 0; c < doNotSilenceAttributes.size(); c++) {
            var doNotSilenceAttribute = doNotSilenceAttributes.get(c);
            if (CarAudioContext.getAudioAttributesMatchScore(playback.getAudioAttributes(),
                    doNotSilenceAttribute) == CarAudioContext.ATTR_MATCH_SCORE_EXACT) {
                return false;
            }
        }
        if (infos == null) {
            return true;
        }
        for (int c = 0; c < infos.size(); c++) {
            var info = infos.get(c);
            if (info.getClientUid() != playback.getClientUid()) {
                continue;
            }
            if (usageAndTagsMatch(info.getAttributes(), playback.getAudioAttributes())) {
                return false;
            }
        }
        return true;
    }

    private boolean usageAndTagsMatch(AudioAttributes aa1, AudioAttributes aa2) {
        if (aa1.getSystemUsage() != aa2.getSystemUsage()) {
            return false;
        }

        return removeEmptyTags(AudioManagerHelper.getTags(aa1))
                .equals(removeEmptyTags(AudioManagerHelper.getTags(aa2)));
    }

    private void onAudioFocusChange(List<AudioFocusInfo> focusInfos) {
        Trace.traceBegin(TraceHelper.TRACE_TAG_CAR_SERVICE,
                "CarAudioFocusEnforcement.onAudioFocusChange");
        try {
            List<AudioPlaybackConfiguration> currentPlaybacks;
            boolean relaxedParkModeEnabled;
            boolean hasCriticalAudioFocus;
            synchronized (mLock) {
                mPrimaryZoneFocusHolders = new CopyOnWriteArrayList<>(focusInfos);
                mHasCriticalAudioFocusRequest = hasCriticalAudioFocusRequest(focusInfos);
                currentPlaybacks = mPrimaryZoneActivePlaybacks;
                relaxedParkModeEnabled = mRelaxedParkModeEnabled;
                hasCriticalAudioFocus = mHasCriticalAudioFocusRequest;
            }
            evaluateAndEnforceFocusState(currentPlaybacks, focusInfos, relaxedParkModeEnabled,
                    hasCriticalAudioFocus);
        } finally {
            Trace.traceEnd(TraceHelper.TRACE_TAG_CAR_SERVICE);
        }
    }

    private boolean hasCriticalAudioFocusRequest(List<AudioFocusInfo> focusHolders) {
        for (int i = 0; i < focusHolders.size(); i++) {
            if (isCriticalAudioUsage(focusHolders.get(i).getAttributes().getSystemUsage())) {
                return true;
            }
        }
        return false;
    }

    private Set<String> removeEmptyTags(Set<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Collections.emptySet();
        }
        return tags.stream()
                .filter(tag -> !TextUtils.isEmpty(tag) && !tag.trim().isEmpty())
                .collect(Collectors.toSet());
    }

    void onFocusChange(SparseArray<List<AudioFocusInfo>> focusHoldersByZoneId) {
        Trace.traceBegin(TraceHelper.TRACE_TAG_CAR_SERVICE,
                "CarAudioFocusEnforcement.onFocusChange");
        try {
            Objects.requireNonNull(focusHoldersByZoneId,
                    "Focus holders by zone id's can not be null");
            for (int i = 0; i < focusHoldersByZoneId.size(); i++) {
                int zoneId = focusHoldersByZoneId.keyAt(i);
                if (zoneId != PRIMARY_AUDIO_ZONE) {
                    continue;
                }
                var holdersInZone = focusHoldersByZoneId.get(zoneId);
                Objects.requireNonNull(holdersInZone, "Focus holders in zone can not be null");
                onAudioFocusChange(holdersInZone);
            }
        } finally {
            Trace.traceEnd(TraceHelper.TRACE_TAG_CAR_SERVICE);
        }
    }

    void onAudioPlaybackChange(
            SparseArray<List<AudioPlaybackConfiguration>> activePlaybackByZoneId) {
        Trace.traceBegin(TraceHelper.TRACE_TAG_CAR_SERVICE,
                "CarAudioFocusEnforcement.onAudioPlaybackChange");
        try {
            Objects.requireNonNull(activePlaybackByZoneId,
                    "Active playbacks by zone id's can not be null");
            boolean relaxedParkModeEnabled;
            boolean hasCriticalAudioFocus;
            synchronized (mLock) {
                relaxedParkModeEnabled = mRelaxedParkModeEnabled;
                hasCriticalAudioFocus = mHasCriticalAudioFocusRequest;
            }
            for (int i = 0; i < activePlaybackByZoneId.size(); i++) {
                int zoneId = activePlaybackByZoneId.keyAt(i);
                if (zoneId != PRIMARY_AUDIO_ZONE) {
                    continue;
                }
                var playbackByZoneId = activePlaybackByZoneId.get(zoneId);
                Objects.requireNonNull(playbackByZoneId,
                        "Active playback in zone can not be null");
                List<AudioFocusInfo> currentFocusHolders;
                synchronized (mLock) {
                    mPrimaryZoneActivePlaybacks = new CopyOnWriteArrayList<>(playbackByZoneId);
                    currentFocusHolders = mPrimaryZoneFocusHolders;
                }
                evaluateAndEnforceFocusState(playbackByZoneId, currentFocusHolders,
                        relaxedParkModeEnabled, hasCriticalAudioFocus);
            }
        } finally {
            Trace.traceEnd(TraceHelper.TRACE_TAG_CAR_SERVICE);
        }
    }

    void enableRelaxedParkMode(boolean enable) {
        Trace.traceBegin(TraceHelper.TRACE_TAG_CAR_SERVICE,
                "CarAudioFocusEnforcement.enableRelaxedParkMode");
        try {
            List<AudioPlaybackConfiguration> currentPlaybacks;
            List<AudioFocusInfo> currentFocusHolders;
            boolean hasCriticalAudioFocusRequest;
            synchronized (mLock) {
                if (mRelaxedParkModeEnabled == enable) {
                    return;
                }
                mRelaxedParkModeEnabled = enable;
                currentPlaybacks = mPrimaryZoneActivePlaybacks;
                currentFocusHolders = mPrimaryZoneFocusHolders;
                hasCriticalAudioFocusRequest = mHasCriticalAudioFocusRequest;
            }

            evaluateAndEnforceFocusState(currentPlaybacks, currentFocusHolders, enable,
                    hasCriticalAudioFocusRequest);
        } finally {
            Trace.traceEnd(TraceHelper.TRACE_TAG_CAR_SERVICE);
        }
    }

    private void handleInformCallbacks(List<EnforcedAudioFocusInfo> enforcedAudioFocusInfos) {
        Trace.traceBegin(TraceHelper.TRACE_TAG_CAR_SERVICE,
                "CarAudioFocusEnforcement.enforceAudioFocus-informCallbacks");
        try {
            if (enforcedAudioFocusInfos.isEmpty()) {
                return;
            }
            int n = mFocusEnforcementCallbacks.beginBroadcast();
            for (int c = 0; c < n; c++) {
                var callback = mFocusEnforcementCallbacks.getBroadcastItem(c);
                Object cookie = mFocusEnforcementCallbacks.getBroadcastCookie(c);
                CallbackIdentity identity = (CallbackIdentity) cookie;
                Trace.traceBegin(TraceHelper.TRACE_TAG_CAR_SERVICE,
                        "CarAudioFocusEnforcement.enforceAudioFocus-informCallbacks-" + identity);
                try {
                    callback.onEnforcedAudioFocusChanged(enforcedAudioFocusInfos);
                } catch (RemoteException e) {
                    Slogf.e(TAG, e, "Could not inform focus enforcement %s", identity);
                } finally {
                    Trace.traceEnd(TraceHelper.TRACE_TAG_CAR_SERVICE);
                }
            }
            mFocusEnforcementCallbacks.finishBroadcast();
        } finally {
            Trace.traceEnd(TraceHelper.TRACE_TAG_CAR_SERVICE);
        }
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    void dump(IndentingPrintWriter writer) {
        synchronized (mLock) {
            writer.increaseIndent();
            writer.printf("Relaxed Park Mode Enabled: %b\n", mRelaxedParkModeEnabled);
            writer.printf("Has Critical Audio Focus Request: %b\n",
                    mHasCriticalAudioFocusRequest);
            dumpEnforceableAttributesLocked(writer);
            dumpDoNotSilenceAttributesLocked(writer);
            dumpSilencedPlayersLocked(writer);
            dumpFocusHoldersByZonesLocked(writer);
            dumpActivePlaybackByZonesLocked(writer);
            dumpEnforcementEventsLocked(writer);
            writer.decreaseIndent();
        }
    }

    @GuardedBy("mLock")
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    private void dumpSilencedPlayersLocked(IndentingPrintWriter writer) {
        writer.println("Silenced players:");
        writer.increaseIndent();
        for (int index = 0; index < mCurrentlySilencedPlayerIDs.size(); index++) {
            writer.printf("%s\n", mCurrentlySilencedPlayerIDs.valueAt(index));
        }
        writer.decreaseIndent();
    }

    @GuardedBy("mLock")
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    private void dumpEnforceableAttributesLocked(IndentingPrintWriter writer) {
        writer.println("Enforceable audio attributes:");
        writer.increaseIndent();
        for (int i = 0; i < mEnforceableAttributes.size(); i++) {
            writer.printf("%s\n", mEnforceableAttributes.valueAt(i));
        }
        writer.decreaseIndent();
    }

    @GuardedBy("mLock")
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    private void dumpDoNotSilenceAttributesLocked(IndentingPrintWriter writer) {
        writer.println("Do not silence audio attributes:");
        writer.increaseIndent();
        for (int i = 0; i < mDoNotSilenceAttributes.size(); i++) {
            writer.printf("%s\n", mDoNotSilenceAttributes.valueAt(i));
        }
        writer.decreaseIndent();
    }

    @GuardedBy("mLock")
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    private void dumpEnforcementEventsLocked(IndentingPrintWriter writer) {
        writer.println("Enforcement Events:");
        writer.increaseIndent();
        mFocusEnforcementLogger.dump(writer);
        writer.decreaseIndent();
    }

    @GuardedBy("mLock")
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    private void dumpFocusHoldersByZonesLocked(IndentingPrintWriter writer) {
        writer.println("Primary Zone Focus holders:");
        writer.increaseIndent();
        for (int index = 0; index < mPrimaryZoneFocusHolders.size(); index++) {
            var holder = mPrimaryZoneFocusHolders.get(index);
            writer.printf("Focus holders[UID=%d]: %s\n",
                    holder.getClientUid(), holder.getAttributes());
        }
        writer.decreaseIndent();
    }

    @GuardedBy("mLock")
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    private void dumpActivePlaybackByZonesLocked(IndentingPrintWriter writer) {
        writer.println("Primary Zone Active playback:");
        writer.increaseIndent();
        for (int c = 0; c < mPrimaryZoneActivePlaybacks.size(); c++) {
            var playback = mPrimaryZoneActivePlaybacks.get(c);
            var canManage = audioPlaybackCanBeSilenced(playback,
                    new ArrayList<>(mEnforceableAttributes));
            writer.printf("%s Playback: %s\n", canManage ? "Manageable" : "Unmanageable",
                    playback);
        }
        writer.decreaseIndent();
    }

    private record CallbackIdentity(int pid, int uid) {

        @Override
        public String toString() {
            return " callback {pid=" + pid + ", uid=" + uid + "}";
        }
    }
}
