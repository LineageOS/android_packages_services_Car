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

import android.content.Context;
import android.graphics.Bitmap;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.car.cluster.navigation.RichText;
import androidx.car.cluster.navigation.RichTextElement;

/**
 * View component that displays the Cue information on the instrument cluster display
 */
public class CueView extends TextView {
    public CueView(Context context) {
        super(context);
    }

    public CueView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CueView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setRichText(RichText richText) {
        if (richText == null) {
            setText(null);
            return;
        }

        SpannableStringBuilder builder = new SpannableStringBuilder();
        for (RichTextElement textElement : richText.getElements()) {
            if (!textElement.getText().equals("")) {
                builder.append(textElement.getText());
            } else if (textElement.getImage() != null) {
                builder.append(" ");

                Bitmap bitmap = ImageResolver.getInstance().getBitmap(mContext,
                        textElement.getImage());

                if (bitmap != null) {
                    bitmap = Bitmap.createScaledBitmap(bitmap,
                            (int) (((float) getLineHeight() / bitmap.getHeight())
                                    * bitmap.getWidth()),
                            getLineHeight(),
                            true);

                    int index = builder.length() - 1;
                    builder.setSpan(new ImageSpan(mContext, bitmap), index, index + 1, 0);
                }
            }
        }

        setText(builder);
    }
}
