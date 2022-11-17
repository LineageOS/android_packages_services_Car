/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.google.android.car.kitchensink.media;

import static android.R.layout.simple_spinner_dropdown_item;
import static android.R.layout.simple_spinner_item;

import static androidx.core.content.IntentCompat.EXTRA_START_PLAYBACK;

import static com.google.android.car.kitchensink.media.MediaBrowserProxyService.MEDIA_BROWSER_SERVICE_COMPONENT_KEY;

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.media.session.PlaybackState.State;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.R;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;

/**
 * Multi-user multi-display media demo.
 *
 * <p>This is a reference implementation to show that driver can start a YouTube video on
 * a passenger screen and control the media session.
 */
// TODO(b/231456119) Support command line interface to play/resume/prev/next, etc.
public final class MultidisplayMediaFragment extends Fragment {

    private static final String TAG = MultidisplayMediaFragment.class.getSimpleName();

    private static final String INSTALL_YOUTUBE_MESSAGE = "Cannot start a YouTube video."
            + " Follow the instructions on go/mumd-media to install YouTube app.";
    private static final String YOUTUBE_PACKAGE = "com.google.android.youtube";
    // YouTube Activity class to play a video.
    private static final String YOUTUBE_ACTIVITY_CLASS = YOUTUBE_PACKAGE + ".UrlActivity";
    // YouTube MediaBrowserService class.
    private static final String YOUTUBE_MEDIA_BROWSER_SERVICE_CLASS =
            "com.google.android.apps.youtube.app.extensions.mediabrowser.impl"
                    + ".MainAppMediaBrowserService";
    private static final String FORCE_FULLSCREEN = "force_fullscreen";
    private static final String YOUTUBE_WATCH_URL_BASE = "https://www.youtube.com/watch?v=";

    private static final int MAX_NUM_USERS = 5;
    private static final String PAUSE = "Pause";
    private static final String RESUME = "Resume";

    // Pre-defined list of videos to show for the demo.
    private static final ImmutableMap<String, String> VIDEOS = ImmutableMap.of(
            "Baby Shark", "48XeLQOok_U",
            "Bruno Mars - The Lazy Song", "fLexgOxsZu0",
            "BTS - Butter", "WMweEpGlu_U",
            "PSY - GANGNAM STYLE", "9bZkp7q19f0",
            "Rick Astley - Never Gonna Give You Up", "dQw4w9WgXcQ");

    private final SparseArray<Context> mUserContexts = new SparseArray<>(MAX_NUM_USERS);
    private final SparseArray<MediaBrowser> mMediaBrowsers = new SparseArray<>(MAX_NUM_USERS);
    private final SparseArray<MediaController> mMediaControllers = new SparseArray<>(MAX_NUM_USERS);

    private UserManager mUserManager;

    private Spinner mUserSpinner;
    private Spinner mVideoSpinner;
    private Button mPauseResumeButton;
    private Button mPrevButton;
    private Button mNextButton;
    private TextView mNowPlaying;
    private int mSelectedUserId;

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        mUserManager = getContext().getSystemService(UserManager.class);

        View view = inflater.inflate(R.layout.multidisplay_media, container, false);
        initUserSpinner(view);
        initVideoSpinner(view);

        Button startButton = view.findViewById(R.id.start);
        startButton.setOnClickListener(this::onClickStart);

        mPauseResumeButton = view.findViewById(R.id.pause_resume);
        mPauseResumeButton.setOnClickListener(this::onClickPauseResume);
        mPrevButton = view.findViewById(R.id.previous);
        mPrevButton.setOnClickListener(this::onClickPrev);
        mNextButton = view.findViewById(R.id.next);
        mNextButton.setOnClickListener(this::onClickNext);
        setMediaButtonsEnabled(false);

        mNowPlaying = view.findViewById(R.id.now_playing);

        refreshUi();
        return view;
    }

    private void refreshUi() {
        MediaController mediaController = mMediaControllers.get(mSelectedUserId);
        int state = PlaybackState.STATE_NONE;
        MediaMetadata metadata = null;
        if (mediaController != null) {
            metadata = mediaController.getMetadata();
            if (mediaController.getPlaybackState() != null) {
                state = mediaController.getPlaybackState().getState();
            }
        }

        updateNowPlaying(metadata);
        updateButtons(state);
    }

    /** Updates the "Now Playing" media title. */
    private void updateNowPlaying(MediaMetadata metadata) {
        CharSequence title = null;
        if (metadata != null && metadata.getDescription() != null) {
            title = metadata.getDescription().getTitle();
        }
        mNowPlaying.setText("Now playing: " + MoreObjects.firstNonNull(title, ""));
    }

    /** Updates the button names and enable/disable buttons based on media playback state. */
    private void updateButtons(@State int state) {
        boolean enabled = false;
        if (state != PlaybackState.STATE_NONE) {
            enabled = true;
        }
        setMediaButtonsEnabled(enabled);
        mPrevButton.setText(state == PlaybackState.STATE_PLAYING ? PAUSE : RESUME);
    }

    private void setMediaButtonsEnabled(boolean enabled) {
        mPauseResumeButton.setEnabled(enabled);
        mPrevButton.setEnabled(enabled);
        mNextButton.setEnabled(enabled);
    }

    private void connectMediaBrowser() {
        MediaBrowser mediaBrowser = getOrCreateMediaBrowser(mSelectedUserId);
        if (mediaBrowser != null && !mediaBrowser.isConnected()) {
            Log.d(TAG, "Connecting to media browser service for user " + mSelectedUserId);
            mediaBrowser.connect();
        }
    }

    private void disconnectMediaBrowser() {
        MediaBrowser mediaBrowser = mMediaBrowsers.get(mSelectedUserId);
        if (mediaBrowser != null && mediaBrowser.isConnected()) {
            Log.d(TAG, "Disconnecting to media browser service for user " + mSelectedUserId);
            mediaBrowser.disconnect();
            mMediaControllers.set(mSelectedUserId, null);
        }
    }

    /** Sends a YouTube video to the currently selected passenger user. */
    private void onClickStart(View view) {
        Log.d(TAG, "onClickStart() for user " + mSelectedUserId);
        String video = (String) mVideoSpinner.getSelectedItem();
        String videoId = VIDEOS.get(video);

        Uri uri = Uri.parse(YOUTUBE_WATCH_URL_BASE + videoId);
        Log.i(TAG, "Playing youtube uri " + uri + " for user " + mSelectedUserId);

        Intent intent = createPlayIntent(uri);
        Log.d(TAG, "Starting Activity with intent: " + intent);

        try {
            getContext().startActivityAsUser(intent, UserHandle.of(mSelectedUserId));
        } catch (Exception e) {
            Log.e(TAG, "Failed to start video " + video + "for user " + mSelectedUserId, e);
            Toast.makeText(getContext(), INSTALL_YOUTUBE_MESSAGE, Toast.LENGTH_LONG).show();
            return;
        }

        connectMediaBrowser();
        mPauseResumeButton.setText(PAUSE);
    }

    private void onClickPauseResume(View view) {
        Log.d(TAG, "onClickPlayResume() for user " + mSelectedUserId);
        MediaController mediaController = mMediaControllers.get(mSelectedUserId);
        if (mediaController == null) {
            Log.e(TAG, "mediaController is null for user " + mSelectedUserId);
            return;
        }
        MediaController.TransportControls transportControls =
                mediaController.getTransportControls();
        if (transportControls == null) {
            Log.e(TAG, "transport control is null for user " + mSelectedUserId);
        }
        PlaybackState playbackState = mediaController.getPlaybackState();
        Log.d(TAG, "onClickPlayResume() playbackState: " + playbackState);

        int state = playbackState == null ? PlaybackState.STATE_NONE : playbackState.getState();
        if (state == PlaybackState.STATE_PLAYING) {
            transportControls.pause();
        } else {
            transportControls.play();
        }
    }

    private void onClickNext(View view) {
        Log.d(TAG, "onClickNext() for user " + mSelectedUserId);
        MediaController mediaController = mMediaControllers.get(mSelectedUserId);
        if (mediaController == null) {
            Log.e(TAG, "mediaController is null for user " + mSelectedUserId);
            return;
        }
        MediaController.TransportControls transportControls =
                mediaController.getTransportControls();
        if (transportControls == null) {
            Log.e(TAG, "transport control is null for user " + mSelectedUserId);
        }

        transportControls.skipToNext();
    }

    private void onClickPrev(View view) {
        Log.d(TAG, "onClickPrev() for user " + mSelectedUserId);
        MediaController mediaController = mMediaControllers.get(mSelectedUserId);
        if (mediaController == null) {
            Log.e(TAG, "mediaController is null for user " + mSelectedUserId);
            return;
        }
        MediaController.TransportControls transportControls =
                mediaController.getTransportControls();
        if (transportControls == null) {
            Log.e(TAG, "transport control is null for user " + mSelectedUserId);
        }

        transportControls.skipToPrevious();
    }

    /** Initializes the user spinner with the visible users, excluding the driver user. */
    private void initUserSpinner(View view) {
        mUserSpinner = view.findViewById(R.id.user_spinner);

        int currentUserId = ActivityManager.getCurrentUser();
        ArrayList<Integer> userIds = new ArrayList<>();
        Set<UserHandle> visibleUsers = mUserManager.getVisibleUsers();
        for (Iterator<UserHandle> iterator = visibleUsers.iterator(); iterator.hasNext(); ) {
            UserHandle userHandle = iterator.next();
            int userId = userHandle.getIdentifier();
            if (userId == currentUserId) {
                // Skip the current user (driver).
                continue;
            }
            userIds.add(userHandle.getIdentifier());
        }

        ArrayAdapter<Integer> adapter =
                new ArrayAdapter<>(getContext(), simple_spinner_item, userIds);
        adapter.setDropDownViewResource(simple_spinner_dropdown_item);
        mUserSpinner.setAdapter(adapter);
        mUserSpinner.setOnItemSelectedListener(new UserSwitcher());

        mSelectedUserId = (Integer) mUserSpinner.getSelectedItem();
    }

    private void initVideoSpinner(View view) {
        mVideoSpinner = view.findViewById(R.id.video_spinner);

        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(getContext(), simple_spinner_item,
                        new ArrayList<>(VIDEOS.keySet()));
        adapter.setDropDownViewResource(simple_spinner_dropdown_item);
        mVideoSpinner.setAdapter(adapter);
    }

    /**
     * Creates a bundle that contains the actual MediaBrowserService component to pass to the proxy
     * service, so that proxy can connect to it.
     */
    private static Bundle createBundleWithMediaBrowserServiceComponent() {
        String mediaBrowserService = new ComponentName(
                YOUTUBE_PACKAGE, YOUTUBE_MEDIA_BROWSER_SERVICE_CLASS).flattenToString();
        Bundle bundle = new Bundle();
        bundle.putString(MEDIA_BROWSER_SERVICE_COMPONENT_KEY, mediaBrowserService);

        return bundle;
    }

    private static Intent createPlayIntent(Uri uri) {
        return new Intent(Intent.ACTION_VIEW, uri)
                .setClassName(YOUTUBE_PACKAGE, YOUTUBE_ACTIVITY_CLASS)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_START_PLAYBACK, true)
                .putExtra(FORCE_FULLSCREEN, false);
    }

    private Context getOrCreateUserContext(@UserIdInt int userId) {
        Context userContext = mUserContexts.get(userId);
        if (userContext != null) {
            return userContext;
        }

        UserHandle userHandle = UserHandle.of(userId);
        Context context = getContext();
        if (!userHandle.equals(Process.myUserHandle())) {
            try {
                context = context.createContextAsUser(userHandle, /* flags= */ 0);
                Log.d(TAG, "Successfully created a context as user " + userId);
            } catch (Exception e) {
                Log.e(TAG, "createContextAsUser() failed for user " + userId);
            }
        }

        mUserContexts.set(userId, context);
        return context;
    }

    private MediaBrowser getOrCreateMediaBrowser(@UserIdInt int userId) {
        MediaBrowser mediaBrowser = mMediaBrowsers.get(userId);
        if (mediaBrowser != null) {
            Log.d(TAG, "User media browser already created for user " + userId);
            return mediaBrowser;
        }

        Context userContext = getOrCreateUserContext(userId);
        mediaBrowser = new MediaBrowser(userContext,
                new ComponentName(userContext, MediaBrowserProxyService.class),
                new ConnectionCallback(userId), createBundleWithMediaBrowserServiceComponent());
        Log.d(TAG, "A MediaBrowser " + mediaBrowser + " created for user "
                + userContext.getUserId());

        mMediaBrowsers.set(userId, mediaBrowser);
        return mediaBrowser;
    }

    private final class ConnectionCallback extends MediaBrowser.ConnectionCallback {

        private final int mUserId;

        ConnectionCallback(@UserIdInt int userId) {
            mUserId = userId;
        }

        @Override
        public void onConnected() {
            Log.d(TAG, "onConnected(): user " + mUserId);
            MediaBrowser mediaBrowser = getOrCreateMediaBrowser(mUserId);
            Log.d(TAG, "onConnected(): user " + mUserId + ", session token "
                    + mediaBrowser.getSessionToken());
            if (mediaBrowser.getSessionToken() == null) {
                throw new IllegalArgumentException("No Session token");
            }

            MediaController mediaController = new MediaController(
                    getOrCreateUserContext(mUserId), mediaBrowser.getSessionToken());
            PlaybackState playbackState = mediaController.getPlaybackState();
            Log.d(TAG, "A MediaController " + mediaController + " created for user "
                    + mUserId + ", playback state: " + playbackState);
            mediaController.registerCallback(new SessionCallback(mUserId));
            mMediaControllers.set(mUserId, mediaController);

            refreshUi();
        }

        @Override
        public void onConnectionFailed() {
            Log.d(TAG, "onConnectionFailed(): user " + mUserId);
            setMediaButtonsEnabled(false);
        }

        @Override
        public void onConnectionSuspended() {
            Log.d(TAG, "onConnectionSuspended(): user " + mUserId);
            setMediaButtonsEnabled(false);
            mMediaControllers.set(mUserId, null);
        }
    };

    private final class SessionCallback extends MediaController.Callback {

        private final int mUserId;

        SessionCallback(@UserIdInt int userId) {
            mUserId = userId;
        }

        @Override
        public void onSessionDestroyed() {
            Log.d(TAG, "onSessionDestroyed(): user " + mUserId);
            MediaBrowser mediaBrowser = mMediaBrowsers.get(mUserId);
            if (mediaBrowser != null) {
                mediaBrowser.disconnect();
            }
            mMediaControllers.set(mUserId, null);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            Log.d(TAG, "onMetadataChanged(): user " + mUserId + " metadata " + metadata);
            if (mSelectedUserId != mUserId) {
                return;
            }
            updateNowPlaying(metadata);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState playbackState) {
            Log.d(TAG, "onPlaybackStateChanged(): user " + mUserId
                    + " playbackState " + playbackState);
            if (mSelectedUserId != mUserId) {
                return;
            }
            updateButtons(playbackState == null
                    ? PlaybackState.STATE_NONE : playbackState.getState());
        }
    }

    private final class UserSwitcher implements AdapterView.OnItemSelectedListener {
        public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
            int selectedUserId = (Integer) parent.getItemAtPosition(pos);
            if (selectedUserId == mSelectedUserId) {
                return;
            }
            disconnectMediaBrowser();
            Log.d(TAG, "Selected user: " + selectedUserId);
            mSelectedUserId = selectedUserId;
            connectMediaBrowser();

            refreshUi();
        }

        public void onNothingSelected(AdapterView<?> parent) {
            // no op.
        }
    }
}
