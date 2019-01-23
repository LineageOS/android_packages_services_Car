/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.car.cluster.navigation.ImageReference;

import java.io.FileNotFoundException;
import java.io.IOException;


/**
 * Class for retrieving bitmap images from a ContentProvider
 */
public class ImageResolver {
    private static final String TAG = "ImageResolver";

    private static ImageResolver sImageResolver = new ImageResolver();

    private ImageResolver() {}

    public static ImageResolver getInstance() {
        return sImageResolver;
    }

    /**
     * Returns a bitmap from an URI string from a content provider
     *
     * @param context View context
     */
    @Nullable
    protected static Bitmap getBitmap(Context context, String uriString) {
        Uri uri = Uri.parse(uriString);
        try {
            ContentResolver contentResolver = context.getContentResolver();
            ParcelFileDescriptor fileDesc = contentResolver.openFileDescriptor(uri, "r");
            if (fileDesc != null) {
                Bitmap bitmap = BitmapFactory.decodeFileDescriptor(fileDesc.getFileDescriptor());
                fileDesc.close();
                return bitmap;
            } else {
                Log.e(TAG, "Null pointer: Failed to create pipe for uri string: " + uriString);
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "File not found for uri string: " + uriString, e);
        } catch (IOException e) {
            Log.e(TAG, "File descriptor could not close: ", e);
        }

        return null;
    }

    /**
     * Returns a bitmap from a Car Instrument Cluster {@link ImageReference}
     *
     * @param context View context
     */
    @Nullable
    protected static Bitmap getBitmap(Context context, ImageReference img) {
        String uriString = img.getRawContentUri();
        return getBitmap(context, uriString);
    }
}
