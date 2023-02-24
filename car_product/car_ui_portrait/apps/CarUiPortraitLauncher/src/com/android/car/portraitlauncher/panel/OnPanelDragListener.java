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

package com.android.car.portraitlauncher.panel;

import android.graphics.Point;
import android.view.MotionEvent;
import android.view.View;

/** An OnTouchListener to control the drag events of a TaskViewPanel */
abstract class OnPanelDragListener implements View.OnTouchListener {

    private Point mDragBeginPoint = new Point();
    private Point mDragCurrentPoint = new Point();
    private Point mDragDeltaPoint = new Point();
    private Point mLastDragDeltaPoint = new Point();

    @Override
    public boolean onTouch(View view, MotionEvent event) {

        mDragCurrentPoint.set((int) event.getRawX(), (int) event.getRawY());

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mDragBeginPoint.set(mDragCurrentPoint);
                mDragDeltaPoint.set(0, 0);
                mLastDragDeltaPoint.set(0, 0);
                onDragBegin();
                break;
            case MotionEvent.ACTION_MOVE:
                mDragDeltaPoint.set(mDragCurrentPoint);
                mDragDeltaPoint.offset(-mDragBeginPoint.x, -mDragBeginPoint.y);
                if (mLastDragDeltaPoint.equals(mDragDeltaPoint)) {
                    return true;
                }
                mLastDragDeltaPoint.set(mDragDeltaPoint);
                onDrag(mDragDeltaPoint.x, mDragDeltaPoint.y);
                break;
            case MotionEvent.ACTION_UP:
                mDragDeltaPoint.set(mDragCurrentPoint);
                mDragDeltaPoint.offset(-mDragBeginPoint.x, -mDragBeginPoint.y);
                onDragEnd(mDragDeltaPoint.x, mDragDeltaPoint.y);
                break;
            default:
                return false;
        }
        return true;
    }

    abstract void onDragBegin();
    abstract void onDrag(int deltaX, int deltaY);
    abstract void onDragEnd(int deltaX, int deltaY);
}
