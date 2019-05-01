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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProviders;

import com.android.car.apps.common.BackgroundImageView;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.source.MediaSourceViewModel;

/**
 * Displays information on the current media item selected.
 */
public class MusicFragment extends Fragment {
    private static final String TAG = "MusicFragment";

    public MusicFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        FragmentActivity activity = requireActivity();
        PlaybackViewModel playbackViewModel = PlaybackViewModel.get(activity.getApplication());
        MediaSourceViewModel mMediaSourceViewModel = MediaSourceViewModel.get(
                activity.getApplication());

        MusicFragmentViewModel innerViewModel = ViewModelProviders.of(activity).get(
                MusicFragmentViewModel.class);
        innerViewModel.init(mMediaSourceViewModel, playbackViewModel);

        View view = inflater.inflate(R.layout.fragment_music, container, false);

        TextView appName = view.findViewById(R.id.app_name);
        innerViewModel.getAppName().observe(getViewLifecycleOwner(), appName::setText);

        TextView title = view.findViewById(R.id.title);
        innerViewModel.getTitle().observe(getViewLifecycleOwner(), title::setText);

        TextView subtitle = view.findViewById(R.id.subtitle);
        innerViewModel.getSubtitle().observe(getViewLifecycleOwner(), subtitle::setText);

        SeekBar seekBar = view.findViewById(R.id.seek_bar);
        innerViewModel.getMaxProgress().observe(getViewLifecycleOwner(),
                maxProgress -> seekBar.setMax(maxProgress != null ? maxProgress.intValue() : 0));
        innerViewModel.getProgress().observe(getViewLifecycleOwner(),
                progress -> seekBar.setProgress((int) progress.getProgress()));
        innerViewModel.hasTime().observe(getViewLifecycleOwner(),
                hasTime -> seekBar.setVisibility(hasTime ? View.VISIBLE : View.INVISIBLE));

        TextView time = view.findViewById(R.id.time);

        innerViewModel.getTimeText().observe(getViewLifecycleOwner(),
                timeText -> time.setText(timeText));

        BackgroundImageView albumBackground = view.findViewById(R.id.album_background);
        ImageView albumIcon = view.findViewById(R.id.album_art);
        innerViewModel.getAlbumArt().observe(getViewLifecycleOwner(), albumArt -> {
            albumBackground.setBackgroundImage(albumArt, true);
            if (albumArt == null) {
                albumIcon.setImageDrawable(getContext().getDrawable(R.drawable.ic_person));
            } else {
                albumIcon.setImageBitmap(albumArt);
            }
        });

        return view;
    }
}
