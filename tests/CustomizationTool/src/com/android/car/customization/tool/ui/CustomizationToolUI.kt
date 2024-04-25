/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.car.customization.tool.R
import com.android.car.customization.tool.domain.Action
import com.android.car.customization.tool.domain.Observer
import com.android.car.customization.tool.domain.PageState
import com.android.car.customization.tool.domain.ToggleUiAction
import com.android.car.customization.tool.ui.menu.HorizontalMarginItemDecoration
import com.android.car.customization.tool.ui.menu.MenuAdapter
import com.android.car.customization.tool.ui.panel.header.PanelHeaderAdapter
import com.android.car.customization.tool.ui.panel.items.PanelItemsAdapter
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the UI of the tool.
 */
internal class CustomizationToolUI @Inject constructor(
    private val windowManager: WindowManager,
    layoutInflater: LayoutInflater,
) : Observer<PageState> {

    lateinit var handleAction: (action: Action) -> Unit

    private val windowLayoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSPARENT
        flags = flags or FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        y = 0
        x = 0
    }
    private val mMainLayout: MainLayoutView = layoutInflater.inflate(
        R.layout.main_layout,
        /*root=*/null
    ) as MainLayoutView
    private val mEntryPoint: ImageButton
    private val mMenu: RecyclerView

    private val mControlPanelContainer: LinearLayout
    private val mControlPanelHeader: RecyclerView
    private val mControlPanelItems: RecyclerView

    private var usableWindowWidth: Int = calculateUsableWidth()

    init {
        setWindowMoveAnimation(false)
        mMainLayout.setTouchListener { inside -> setKeyboardActive(inside) }
        windowManager.addView(mMainLayout, windowLayoutParams)

        mEntryPoint = mMainLayout.requireViewById<ImageButton>(R.id.entry_point).apply {
            setOnClickListener {
                handleAction(ToggleUiAction)
            }
        }

        mMenu = mMainLayout.requireViewById<RecyclerView>(R.id.menu).apply {
            layoutManager = LinearLayoutManager(
                context,
                LinearLayoutManager.HORIZONTAL,
                /*reverseLayout=*/false
            )
            itemAnimator = null
            addItemDecoration(
                HorizontalMarginItemDecoration(
                    resources.getDimensionPixelSize(R.dimen.menu_items_horizontal_margin)
                )
            )
        }

        mControlPanelContainer = mMainLayout.requireViewById(R.id.control_panel_container)
        mControlPanelHeader = mMainLayout
            .requireViewById<RecyclerView>(R.id.control_panel_header)
            .apply {
                layoutManager = LinearLayoutManager(
                    context,
                    LinearLayoutManager.HORIZONTAL,
                    /*reverseLayout=*/ false
                )
                itemAnimator = null
            }
        mControlPanelItems = mMainLayout.requireViewById<RecyclerView>(R.id.control_panel_items).apply {
            layoutManager = LinearLayoutManager(
                context,
                LinearLayoutManager.VERTICAL,
                /*reverseLayout=*/false
            )
            itemAnimator = null
        }

        mMainLayout.setOnApplyWindowInsetsListener { _, insets ->
            val newUsableWindowWidth = calculateUsableWidth()
            if (newUsableWindowWidth != usableWindowWidth) {
                usableWindowWidth = newUsableWindowWidth
                updateToolWidth()
            }
            insets
        }
    }

    override fun render(state: PageState) {
        if (!state.isOpen) {
            mMenu.visibility = View.GONE
            mControlPanelContainer.visibility = View.GONE
        } else {
            mMenu.run {
                if (adapter == null) adapter = MenuAdapter(handleAction)
                (adapter as MenuAdapter).submitList(state.menu.currentParentNode.subMenu)
                visibility = View.VISIBLE
            }

            if (mControlPanelItems.adapter == null) {
                mControlPanelItems.adapter = PanelItemsAdapter(handleAction)
            }
            if (mControlPanelHeader.adapter == null) {
                mControlPanelHeader.adapter = PanelHeaderAdapter(handleAction)
            }
            if (state.panel != null) {
                (mControlPanelHeader.adapter as PanelHeaderAdapter).submitList(state.panel.headerItems)
                (mControlPanelItems.adapter as PanelItemsAdapter).submitList(state.panel.items)
                mControlPanelContainer.visibility = View.VISIBLE
            } else {
                (mControlPanelHeader.adapter as PanelHeaderAdapter).submitList(listOf())
                (mControlPanelItems.adapter as PanelItemsAdapter).submitList(listOf())
                mControlPanelContainer.visibility = View.GONE
            }
        }

        waitForMeasureAndUpdateWidth()
    }

    /**
     * A utility function to calculate the necessary width of the tool.
     *
     * On bigger screens WRAP_CONTENT doesn't behave correctly for dialogs because of a size limit
     * defined in the config_prefDialogWidth property. For this reason
     * the width of the tool is calculated manually based on the content displayed.
     */
    private fun waitForMeasureAndUpdateWidth() {
        mMainLayout.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    mMainLayout.viewTreeObserver.removeOnGlobalLayoutListener(/*victim=*/this)
                    usableWindowWidth = calculateUsableWidth()
                    updateToolWidth()
                }
            }
        )
    }

    private fun calculateUsableWidth(): Int {
        val windowMetrics = windowManager.currentWindowMetrics
        val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
            /*typeMask=*/WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()
        )
        return windowMetrics.bounds.width() - insets.right - insets.left
    }

    private fun updateToolWidth() {
        val windowMetrics = windowManager.currentWindowMetrics
        mMainLayout.measure(
            windowMetrics.bounds.width(),
            windowMetrics.bounds.height()
        )
        val mainWidth = mMainLayout.measuredWidth

        val panelMinWidth = if (mControlPanelContainer.visibility == View.VISIBLE) {
            (usableWindowWidth * 0.8).toInt()
        } else {
            -1
        }

        windowLayoutParams.width = min(max(mainWidth, panelMinWidth), usableWindowWidth)
        windowManager.updateViewLayout(mMainLayout, windowLayoutParams)
    }

    /**
     * Accessing a PRIVATE_FLAG_NO_MOVE_ANIMATION to enable or disable window movement animation.
     *
     * Disabling the window animation prevents the UI from "shaking" when the window changes width,
     * while enabling it allows the window to move smoothly while dragging the tool.
     */
    private fun setWindowMoveAnimation(enable: Boolean) {
        try {
            val privateFlagsField = windowLayoutParams.javaClass.getField("privateFlags")
            val newValue = if (enable) {
                (privateFlagsField.get(windowLayoutParams) as Int) and (1 shl 6).inv()
            } else {
                (privateFlagsField.get(windowLayoutParams) as Int) or (1 shl 6)
            }
            privateFlagsField.set(windowLayoutParams, newValue)
        } catch (e: Exception) {
            Log.e("CustomizationToolUi", "Setting window animation failed! \n $e")
        }
    }

    /**
     * Changing flags on [WindowManager.LayoutParams] based on clicks inside or outside the Window
     * to enable / disable keyboard access in the tool.
     */
    private fun setKeyboardActive(active: Boolean) {
        if (active && windowLayoutParams.flags and FLAG_NOT_FOCUSABLE != 0) {
            windowLayoutParams.flags = windowLayoutParams.flags and FLAG_NOT_FOCUSABLE.inv()
            windowManager.updateViewLayout(mMainLayout, windowLayoutParams)
        } else if (!active && windowLayoutParams.flags and FLAG_NOT_FOCUSABLE == 0) {
            windowLayoutParams.flags = windowLayoutParams.flags or FLAG_NOT_FOCUSABLE
            windowManager.updateViewLayout(mMainLayout, windowLayoutParams)
        }
    }
}
