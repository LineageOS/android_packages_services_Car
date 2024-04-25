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

package com.android.car.customization.tool.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * Represents the main layout view of the tool. A [LinearLayout] with an additional touch listener
 * that returns true or false when touches are triggered inside or outside the layout.
 */
class MainLayoutView : LinearLayout {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(
        context: Context,
        attrs: AttributeSet,
        defStyleAttr: Int
    ) : super(context, attrs, defStyleAttr)

    private var touchListener: ((inside: Boolean) -> Unit)? = null

    fun setTouchListener(listener: (inside: Boolean) -> Unit) {
        touchListener = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) touchListener?.invoke(/* inside = */true)
        if (event.action == MotionEvent.ACTION_OUTSIDE) touchListener?.invoke(/* inside = */false)
        return super.onTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) touchListener?.invoke(/* inside = */true)
        if (event.action == MotionEvent.ACTION_OUTSIDE) touchListener?.invoke(/* inside = */false)
        return super.onInterceptTouchEvent(event)
    }

}