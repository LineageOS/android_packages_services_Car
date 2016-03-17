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
package android.car.cluster.demorenderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * This class is responsible for drawing the whole instrument cluster.
 */
public class DemoInstrumentClusterView extends FrameLayout {

    private final String TAG = DemoInstrumentClusterView.class.getSimpleName();

    private TextView speedView;
    private TextView eventTitleView;
    private TextView distanceView;
    private View navPanel;
    private TextView mediaArtistView;
    private TextView mediaAlbumView;
    private TextView mediaTrackView;
    private ImageView mediaImageView;
    private View mediaPanel;

    private View phonePanel;
    private TextView phoneTitle;
    private TextView phoneSubtitle;
    private ImageView phoneImage;

    public DemoInstrumentClusterView(Context context) {
        super(context);
        init();
    }

    public void setSpeed(String speed) {
        Log.d(TAG, "setSpeed, meterPerSecond: " + speed);
        speedView.setText(speed);
    }

    public void setFuelLevel(float fuelLevel) {
        Log.d(TAG, "setFuelLevel, fuelLevel: " + fuelLevel);
    }

    public void setFuelRangeVisible(boolean visible) {
        Log.d(TAG, "setFuelRangeVisible, visible: " + visible);
    }

    public void setFuelRange(int rangeMeters) {
        Log.d(TAG, "setFuelRange, rangeMeters: " + rangeMeters);
    }

    public void showNavigation() {
        Log.d(TAG, "showNavigation");
        eventTitleView.setText("");
        distanceView.setText("");
        navPanel.setVisibility(VISIBLE);
    }

    public void hideNavigation() {
        Log.d(TAG, "hideNavigation");
        navPanel.setVisibility(INVISIBLE);
    }

    public void setNextTurn(Bitmap image, String title) {
        Log.d(TAG, "setNextTurn, image: " + image + ", title: " + title);
        eventTitleView.setText(title);
    }

    public void setNextTurnDistance(String distance) {
        Log.d(TAG, "setNextTurnDistance, distance: " + distance);
        distanceView.setText(distance);
    }

    public void setMediaData(final CharSequence artist, final CharSequence album,
            final CharSequence track, final Bitmap image) {
        Log.d(TAG, "setMediaData" + " artist = " + artist + ", album: " + album + ", track: " +
                track + ", bitmap: " + image);

        mediaArtistView.setText(artist);
        mediaAlbumView.setText(album);
        mediaTrackView.setText(track);
        mediaImageView.setImageBitmap(image);
    }

    public void showMedia() {
        Log.d(TAG, "showMedia");
        mediaPanel.setVisibility(VISIBLE);
    }

    public void hideMedia() {
        Log.d(TAG, "hideMedia");
        mediaPanel.setVisibility(GONE);
    }

    public void showPhone() {
        Log.d(TAG, "showPhone");
        phoneSubtitle.setText("");
        phoneImage.setImageResource(0); // To clear previous contact photo (if any).
        phoneTitle.setText("");
        phonePanel.setVisibility(VISIBLE);
    }

    public void hidePhone() {
        Log.d(TAG, "hidePhone");
        phonePanel.setVisibility(GONE);
    }

    public void setPhoneTitle(String number) {
        Log.d(TAG, "setPhoneTitle, number: " + number);
        phoneTitle.setText(number);
    }

    public void setPhoneSubtitle(String contact) {
        Log.d(TAG, "setPhoneContact, contact: " + contact);
        phoneSubtitle.setText(contact);
    }

    public void setPhoneImage(Bitmap photo) {
        Log.d(TAG, "setPhoneImage, photo: " + photo);
        phoneImage.setImageBitmap(photo);
    }

    private void init() {
        Log.d(TAG, "init");
        View rootView = inflate(getContext(), R.layout.instrument_cluster, null);
        speedView = (TextView) rootView.findViewById(R.id.speed);
        eventTitleView = (TextView) rootView.findViewById(R.id.nav_event_title);
        distanceView = (TextView) rootView.findViewById(R.id.nav_distance);
        navPanel = rootView.findViewById(R.id.nav_layout);

        mediaPanel = rootView.findViewById(R.id.media_layout);
        mediaArtistView = (TextView) rootView.findViewById(R.id.media_artist);
        mediaAlbumView = (TextView) rootView.findViewById(R.id.media_album);
        mediaTrackView = (TextView) rootView.findViewById(R.id.media_track);
        mediaImageView = (ImageView) rootView.findViewById(R.id.media_image);

        phonePanel = rootView.findViewById(R.id.phone_layout);
        phoneImage = (ImageView) rootView.findViewById(R.id.phone_contact_photo);
        phoneSubtitle = (TextView) rootView.findViewById(R.id.phone_subtitle);
        phoneTitle = (TextView) rootView.findViewById(R.id.phone_title);

        setSpeed("0");

        addView(rootView);
    }
}
