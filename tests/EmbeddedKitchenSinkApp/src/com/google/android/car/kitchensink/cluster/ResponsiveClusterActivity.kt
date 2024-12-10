/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.google.android.car.kitchensink.cluster

import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.car.kitchensink.R

class ResponsiveClusterActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_responsive_cluster)
        val availableWidth = resources.displayMetrics.widthPixels
        val bar = findViewById<ProgressBar>(R.id.progressBar)!!

        window.decorView.findViewById<View>(R.id.root)!!
            .setOnApplyWindowInsetsListener { v, insets ->
                val systemBars = insets.getInsets(WindowInsets.Type.systemOverlays())
                var params = v.layoutParams as FrameLayout.LayoutParams
                params.setMargins(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    systemBars.bottom
                )
                v.layoutParams = params
                val widthAfterInsets = availableWidth - systemBars.right - systemBars.left
                val progress = 100 * (widthAfterInsets.toFloat()) / availableWidth
                bar.setProgress(progress.toInt())
                insets
            }
    }
}
