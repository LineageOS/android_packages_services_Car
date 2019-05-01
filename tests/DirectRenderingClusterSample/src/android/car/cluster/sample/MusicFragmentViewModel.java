/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.car.cluster.sample;

import static androidx.lifecycle.Transformations.map;

import static com.android.car.arch.common.LiveDataFunctions.combine;
import static com.android.car.arch.common.LiveDataFunctions.mapNonNull;

import android.annotation.SuppressLint;
import android.app.Application;
import android.graphics.Bitmap;
import android.media.session.PlaybackState;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.playback.AlbumArtLiveData;
import com.android.car.media.common.playback.PlaybackProgress;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceViewModel;

import com.bumptech.glide.request.target.Target;

import java.util.concurrent.TimeUnit;

/**
 * View model for {@link MusicFragment}
 */
public final class MusicFragmentViewModel extends AndroidViewModel {

    private LiveData<MediaSource> mMediaSource;
    private LiveData<CharSequence> mAppName;
    private LiveData<Bitmap> mAppIcon;
    private LiveData<CharSequence> mTitle;
    private LiveData<CharSequence> mSubtitle;
    private LiveData<Bitmap> mAlbumArt;
    private LiveData<PlaybackProgress> mProgress;
    private LiveData<Long> mMaxProgress;
    private LiveData<CharSequence> mTimeText;
    private LiveData<Boolean> mHasTime;

    private PlaybackViewModel mPlaybackViewModel;
    private MediaSourceViewModel mMediaSourceViewModel;

    public MusicFragmentViewModel(Application application) {
        super(application);
    }

    void init(MediaSourceViewModel mediaSourceViewModel, PlaybackViewModel playbackViewModel) {
        if (mMediaSourceViewModel == mediaSourceViewModel
                && mPlaybackViewModel == playbackViewModel) {
            return;
        }
        mPlaybackViewModel = playbackViewModel;
        mMediaSourceViewModel = mediaSourceViewModel;
        mMediaSource = mMediaSourceViewModel.getPrimaryMediaSource();
        mAppName = mapNonNull(mMediaSource, MediaSource::getName);
        mAppIcon = mapNonNull(mMediaSource, MediaSource::getRoundPackageIcon);
        mTitle = mapNonNull(playbackViewModel.getMetadata(), MediaItemMetadata::getTitle);
        mSubtitle = mapNonNull(playbackViewModel.getMetadata(), MediaItemMetadata::getSubtitle);
        mAlbumArt = AlbumArtLiveData.getAlbumArt(getApplication(),
                Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL, false,
                playbackViewModel.getMetadata());
        mProgress = playbackViewModel.getProgress();
        mMaxProgress = map(playbackViewModel.getPlaybackStateWrapper(),
                state -> state != null ? state.getMaxProgress() : 0L);
        mTimeText = combine(mProgress, mMaxProgress, (progress, maxProgress) -> {
            boolean showHours = TimeUnit.MILLISECONDS.toHours(maxProgress) > 0;
            return String.format("%s / %s",
                    formatTime(progress.getProgress(), showHours),
                    formatTime(maxProgress, showHours));
        });
        mHasTime = combine(mProgress, mMaxProgress, (progress, maxProgress) ->
                maxProgress > 0
                        && progress.getProgress() != PlaybackState.PLAYBACK_POSITION_UNKNOWN);
    }

    LiveData<CharSequence> getAppName() {
        return mAppName;
    }

    LiveData<Bitmap> getAppIcon() {
        return mAppIcon;
    }

    LiveData<CharSequence> getTitle() {
        return mTitle;
    }

    LiveData<CharSequence> getSubtitle() {
        return mSubtitle;
    }

    LiveData<Bitmap> getAlbumArt() {
        return mAlbumArt;
    }

    LiveData<PlaybackProgress> getProgress() {
        return mProgress;
    }

    LiveData<Long> getMaxProgress() {
        return mMaxProgress;
    }

    LiveData<CharSequence> getTimeText() {
        return mTimeText;
    }

    LiveData<Boolean> hasTime() {
        return mHasTime;
    }

    @SuppressLint("DefaultLocale")
    private static String formatTime(long millis, boolean showHours) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % TimeUnit.HOURS.toMinutes(1);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % TimeUnit.MINUTES.toSeconds(1);
        if (showHours) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%d:%02d", minutes, seconds);
        }
    }
}
