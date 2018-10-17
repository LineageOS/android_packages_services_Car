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
package com.android.car;

import android.hardware.automotive.audiocontrol.V1_0.ContextNumber;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioPolicy;
import android.util.Log;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;


public class CarAudioFocus extends AudioPolicy.AudioPolicyFocusListener {

    private static final String TAG = "CarAudioFocus";

    private final AudioManager  mAudioManager;
    private CarAudioService     mCarAudioService;   // Dynamically assigned just after construction
    private AudioPolicy         mAudioPolicy;       // Dynamically assigned just after construction


    // Values for the internal interaction matrix we use to make focus decisions
    private static final int INTERACTION_REJECT     = 0;    // Focus not granted
    private static final int INTERACTION_EXCLUSIVE  = 1;    // Focus granted, others loose focus
    private static final int INTERACTION_CONCURRENT = 2;    // Focus granted, others keep focus

    // TODO:  Make this an overlayable resource...
    //  MUSIC           = 1,        // Music playback
    //  NAVIGATION      = 2,        // Navigation directions
    //  VOICE_COMMAND   = 3,        // Voice command session
    //  CALL_RING       = 4,        // Voice call ringing
    //  CALL            = 5,        // Voice call
    //  ALARM           = 6,        // Alarm sound from Android
    //  NOTIFICATION    = 7,        // Notifications
    //  SYSTEM_SOUND    = 8,        // User interaction sounds (button clicks, etc)
    private static int sInteractionMatrix[][] = {
        // Row selected by playing sound (labels along the right)
        // Column selected by incoming request (labels along the top)
        // Cell value is one of INTERACTION_REJECT, INTERACTION_EXCLUSIVE, INTERACTION_CONCURRENT
        // Invalid, Music, Nav, Voice, Ring, Call, Alarm, Notification, System
        {  0,       0,     0,   0,     0,    0,    0,     0,            0 }, // Invalid
        {  0,       1,     2,   1,     1,    1,    1,     2,            2 }, // Music
        {  0,       2,     2,   1,     2,    1,    2,     2,            2 }, // Nav
        {  0,       2,     0,   2,     1,    1,    0,     0,            0 }, // Voice
        {  0,       0,     2,   2,     2,    2,    0,     0,            2 }, // Ring
        {  0,       0,     2,   0,     2,    2,    2,     2,            0 }, // Context
        {  0,       2,     2,   1,     1,    1,    2,     2,            2 }, // Alarm
        {  0,       2,     2,   1,     1,    1,    2,     2,            2 }, // Notification
        {  0,       2,     2,   1,     1,    1,    2,     2,            2 }, // System
    };


    private class FocusEntry {
        // Requester info
        final AudioFocusInfo mAfi;                      // never null

        final int mAudioContext;                        // Which HAL level context does this affect
        final ArrayList<FocusEntry> mBlockers;          // List of requests that block ours

        FocusEntry(AudioFocusInfo afi,
                   int context) {
            mAfi             = afi;
            mAudioContext    = context;
            mBlockers        = new ArrayList<FocusEntry>();
        }

        public String getClientId() {
            return mAfi.getClientId();
        }

        public boolean wantsPauseInsteadOfDucking() {
            return (mAfi.getFlags() & AudioManager.AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS) != 0;
        }
    }


    // We keep track of all the focus requesters in this map, with their clientId as the key.
    // This is used both for focus dispatch and death handling
    // Note that the clientId reflects the AudioManager instance and listener object (if any)
    // so that one app can have more than one unique clientId by setting up distinct listeners.
    // Because the listener gets only LOSS/GAIN messages, this is important for an app to do if
    // it expects to request focus concurrently for different USAGEs so it knows which USAGE
    // gained or lost focus at any given moment.  If the SAME listener is used for requests of
    // different USAGE while the earlier request is still in the focus stack (whether holding
    // focus or pending), the new request will be REJECTED so as to avoid any confusion about
    // the meaning of subsequent GAIN/LOSS events (which would continue to apply to the focus
    // request that was already active or pending).
    private HashMap<String, FocusEntry> mFocusHolders = new HashMap<String, FocusEntry>();
    private HashMap<String, FocusEntry> mFocusLosers = new HashMap<String, FocusEntry>();


    CarAudioFocus(AudioManager audioManager) {
        mAudioManager = audioManager;
    }


    // This has to happen after the construction to avoid a chicken and egg problem when setting up
    // the AudioPolicy which must depend on this object.
    public void setOwningPolicy(CarAudioService audioService, AudioPolicy parentPolicy) {
        mCarAudioService = audioService;
        mAudioPolicy     = parentPolicy;
    }


    // This sends a focus loss message to the targeted requester.
    private void sendFocusLoss(FocusEntry loser, boolean permanent) {
        int lossType = (permanent ? AudioManager.AUDIOFOCUS_LOSS :
                                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        Log.i(TAG, "sendFocusLoss to " + loser.getClientId());
        int result = mAudioManager.dispatchAudioFocusChange(loser.mAfi, lossType, mAudioPolicy);
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // TODO:  Is this actually an error, or is it okay for an entry in the focus stack
            // to NOT have a listener?  If that's the case, should we even keep it in the focus
            // stack?
            Log.e(TAG, "Failure to signal loss of audio focus with error: " + result);
        }
    }


    /** @see AudioManager#requestAudioFocus(AudioManager.OnAudioFocusChangeListener, int, int, int) */
    // Note that we replicate most, but not all of the behaviors of the default MediaFocusControl
    // engine as of Android P.
    // Besides the interaction matrix which allows concurrent focus for multiple requestors, which
    // is the reason for this module, we also treat repeated requests from the same clientId
    // slightly differently.
    // If a focus request for the same listener (clientId) is received while that listener is
    // already in the focus stack, we REJECT it outright unless it is for the same USAGE.
    // The default audio framework's behavior is to remove the previous entry in the stack (no-op
    // if the requester is already holding focus).
    int evaluateFocusRequest(AudioFocusInfo afi) {
        Log.i(TAG, "Evaluating focus request for client " + afi.getClientId());

        // Is this a request for premanant focus?
        // AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -- Means Notifications should be denied
        // AUDIOFOCUS_GAIN_TRANSIENT -- Means current focus holders should get transient loss
        // AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -- Means other can duck (no loss message from us)
        // NOTE:  We expect that in practice it will be permanent for all media requests and
        //        transient for everything else, but that isn't currently an enforced requirement.
        final boolean permanent =
                (afi.getGainRequest() == AudioManager.AUDIOFOCUS_GAIN);
        final boolean allowDucking =
                (afi.getGainRequest() == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);


        // Convert from audio attributes "usage" to HAL level "context"
        final int requestedContext = mCarAudioService.getContextForUsage(
                afi.getAttributes().getUsage());

        // If we happen find an entry that this new request should replace, we'll store it here.
        FocusEntry deprecatedBlockedEntry = null;

        // Scan all active and pending focus requests.  If any should cause rejection of
        // this new request, then we're done.  Keep a list of those against whom we're exclusive
        // so we can update the relationships if/when we are sure we won't get rejected.
        Log.i(TAG, "Scanning focus holders...");
        final ArrayList<FocusEntry> losers = new ArrayList<FocusEntry>();
        for (FocusEntry entry : mFocusHolders.values()) {
            Log.i(TAG, entry.mAfi.getClientId());

            // If this request is for Notifications and a current focus holder has specified
            // AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, then reject the request.
            // This matches the hardwired behavior in the default audio policy engine which apps
            // might expect (The interaction matrix doesn't have any provision for dealing with
            // override flags like this).
            if ((requestedContext == ContextNumber.NOTIFICATION) &&
                    (entry.mAfi.getGainRequest() ==
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)) {
                return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            }

            // We don't allow sharing listeners (client IDs) between two concurrent requests
            // (because the app would have no way to know to which request a later event applied)
            if (afi.getClientId().equals(entry.mAfi.getClientId())) {
                if (entry.mAudioContext == requestedContext) {
                    // Trivially accept if this request is a duplicate
                    Log.i(TAG, "Duplicate request from focus holder is accepted");
                    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                } else {
                    // Trivially reject a request for a different USAGE
                    Log.i(TAG, "Different request from focus holder is rejected");
                    return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
                }
            }

            // Check the interaction matrix for the relationship between this entry and the request
            switch (sInteractionMatrix[entry.mAudioContext][requestedContext]) {
                case INTERACTION_REJECT:
                    // This request is rejected, so nothing further to do
                    return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
                case INTERACTION_EXCLUSIVE:
                    // The new request will cause this existing entry to lose focus
                    losers.add(entry);
                    break;
                default:
                    // If ducking isn't allowed by the focus requestor, then everybody else
                    // must get a LOSS.
                    // If a focus holder has set the AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS flag,
                    // they must get a LOSS message even if ducking would otherwise be allowed.
                    if ((!allowDucking) ||
                            (entry.mAfi.getFlags() &
                                    AudioManager.AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS) != 0) {
                        // The new request will cause audio book to lose focus and pause
                        losers.add(entry);
                    }
            }
        }
        Log.i(TAG, "Scanning those who've already lost focus...");
        final ArrayList<FocusEntry> blocked = new ArrayList<FocusEntry>();
        for (FocusEntry entry : mFocusLosers.values()) {
            Log.i(TAG, entry.mAfi.getClientId());

            // If this request is for Notifications and a pending focus holder has specified
            // AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, then reject the request
            if ((requestedContext == ContextNumber.NOTIFICATION) &&
                    (entry.mAfi.getGainRequest() ==
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)) {
                return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            }

            // We don't allow sharing listeners (client IDs) between two concurrent requests
            // (because the app would have no way to know to which request a later event applied)
            if (afi.getClientId().equals(entry.mAfi.getClientId())) {
                if (entry.mAudioContext == requestedContext) {
                    // This is a repeat of a request that is currently blocked.
                    // Evaluate it as if it were a new request, but note that we should remove
                    // the old pending request, and move it.
                    // We do not want to evaluate the new request against itself.
                    Log.i(TAG, "Duplicate request while waiting is being evaluated");
                    deprecatedBlockedEntry = entry;
                    continue;
                } else {
                    // Trivially reject a request for a different USAGE
                    Log.i(TAG, "Different request while waiting is rejected");
                    return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
                }
            }

            // Check the interaction matrix for the relationship between this entry and the request
            switch (sInteractionMatrix[entry.mAudioContext][requestedContext]) {
                case INTERACTION_REJECT:
                    // Even though this entry has currently lost focus, the fact that it is
                    // waiting to play means we'll reject this new conflicting request.
                    return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
                case INTERACTION_EXCLUSIVE:
                    // The new request is yet another reason this entry cannot regain focus (yet)
                    blocked.add(entry);
                    break;
                default:
                    // If ducking is not allowed by the requester, or the pending focus holder had
                    // set the AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS flag,
                    // then the pending holder must stay "lost" until this requester goes away.
                    if ((!allowDucking) || entry.wantsPauseInsteadOfDucking()) {
                        // The new request is yet another reason this entry cannot regain focus yet
                        blocked.add(entry);
                    }
            }
        }


        // Now that we've decided we'll grant focus, construct our new FocusEntry
        FocusEntry newEntry = new FocusEntry(afi, requestedContext);


        // Now that we're sure we'll accept this request, update any requests which we would
        // block but are already out of focus but waiting to come back
        for (FocusEntry entry : blocked) {
            // If we're out of focus it must be because somebody is blocking us
            assert !entry.mBlockers.isEmpty();

            if (permanent) {
                // This entry has now lost focus forever
                sendFocusLoss(entry, permanent);
                final FocusEntry deadEntry = mFocusLosers.remove(entry.mAfi.getClientId());
                assert deadEntry != null;
            } else {
                // Note that this new request is yet one more reason we can't (yet) have focus
                entry.mBlockers.add(newEntry);
            }
        }

        // Notify and update any requests which are now losing focus as a result of the new request
        for (FocusEntry entry : losers) {
            // If we have focus (but are about to loose it), nobody should be blocking us yet
            assert entry.mBlockers.isEmpty();

            sendFocusLoss(entry, permanent);

            // The entry no longer holds focus, so take it out of the holders list
            mFocusHolders.remove(entry.mAfi.getClientId());

            if (!permanent) {
                // Add ourselves to the list of requests waiting to get focus back and
                // note why we lost focus so we can tell when it's time to get it back
                mFocusLosers.put(entry.mAfi.getClientId(), entry);
                entry.mBlockers.add(newEntry);
            }
        }

        // If we encountered a duplicate of this request that was pending, but now we're going to
        // grant focus, we need to remove the old pending request (without sending a LOSS message).
        if (deprecatedBlockedEntry != null) {
            mFocusLosers.remove(deprecatedBlockedEntry.mAfi.getClientId());
        }

        // Finally, add the request we're granting to the focus holders' list
        mFocusHolders.put(afi.getClientId(), newEntry);

        Log.i(TAG, "AUDIOFOCUS_REQUEST_GRANTED");
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }


    @Override
    public synchronized void onAudioFocusRequest(AudioFocusInfo afi, int requestResult) {
        Log.i(TAG, "onAudioFocusRequest " + afi);

        int response = evaluateFocusRequest(afi);

        // Post our reply for delivery to the original focus requester
        mAudioManager.setFocusRequestResult(afi, response, mAudioPolicy);
    }


    /**
     * @see AudioManager#abandonAudioFocus(AudioManager.OnAudioFocusChangeListener, AudioAttributes)
     * Note that we'll get this call for a focus holder that dies while in the focus statck, so
     * we don't need to watch for death notifications directly.
     * */
    @Override
    public synchronized void onAudioFocusAbandon(AudioFocusInfo afi) {
        Log.i(TAG, "onAudioFocusAbandon " + afi);

        // Remove this entry from our active or pending list
        FocusEntry deadEntry = mFocusHolders.remove(afi.getClientId());
        if (deadEntry == null) {
            deadEntry = mFocusLosers.remove(afi.getClientId());
            if (deadEntry == null) {
                // Caller is providing an unrecognzied clientId!?
                Log.e(TAG, "Audio focus abandoned by unrecognzied client id: " + afi.getClientId());
                // NOTE:  We could choose to silently ignore this, but lets make it clear for now
                throw new RuntimeException("Unrecognized client abandoning audio focus");
            }
        }

        // Remove this entry from the blocking list of any pending requests
        Iterator<FocusEntry> it = mFocusLosers.values().iterator();
        while (it.hasNext()) {
            FocusEntry entry = it.next();

            // Remove the retiring entry from all blocker lists
            entry.mBlockers.remove(deadEntry);

            // Any entry whose blocking list becomes empty should regain focus
            if (entry.mBlockers.isEmpty()) {
                // Pull this entry out of the focus losers list
                it.remove();

                // Add it back into the focus holders list
                mFocusHolders.put(entry.getClientId(), entry);

                // Send the focus (re)gain notification
                int result = mAudioManager.dispatchAudioFocusChange(
                        entry.mAfi,
                        entry.mAfi.getGainRequest(),
                        mAudioPolicy);
                if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    // TODO:  Is this actually an error, or is it okay for an entry in the focus
                    // stack to NOT have a listener?  If that's the case, should we even keep
                    // it in the focus stack?
                    Log.e(TAG, "Failure to signal gain of audio focus with error: " + result);
                }
            }
        }
    }


    public synchronized void dump(PrintWriter writer) {
        writer.println("*CarAudioFocus*");

        writer.println("  Current Focus Holders:");
        for (String clientId : mFocusHolders.keySet()) {
            System.out.println(clientId);
        }

        writer.println("  Transient Focus Losers:");
        for (String clientId : mFocusLosers.keySet()) {
            System.out.println(clientId);
        }
    }
}
