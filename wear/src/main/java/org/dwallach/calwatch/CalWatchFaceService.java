/*
 * CalWatch
 * Copyright (C) 2014 by Dan Wallach
 * Home page: http://www.cs.rice.edu/~dwallach/calwatch/
 * Licensing: http://www.cs.rice.edu/~dwallach/calwatch/licensing.html
 */

/*
 * Portions of this file derived from Google's example code,
 * subject to the following:
 *
 * Copyright (C) 2014 The Android Open Source Project
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

package org.dwallach.calwatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.provider.CalendarContract;
import android.support.wearable.provider.WearableCalendarContract;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import org.dwallach.calwatch.WireEvent;

import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.TimeUnit;

/**
 * Drawn heavily from the Android Wear SweepWatchFaceService example code.
 */
public class CalWatchFaceService extends CanvasWatchFaceService {
    private static final String TAG = "CalWatchFaceService";

    /**
     * Update rate in milliseconds for NON-interactive mode. We update once every 12 seconds
     * to advance the minute hand when we're not otherwise sweeping the second hand.
     *
     * The default of one update per minute is ugly so we'll do better.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(12);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements Observer {
        static final int MSG_UPDATE_TIME = 0;

        private ClockFace clockFace;
        private ClockState clockState;
        private CalendarFetcher calendarFetcher;

        /**
         * Handler to tick once every 12 seconds.
         * Used only if the second hand is turned off
         * or in ambient mode so we get smooth minute-hand
         * motion.
         */
        private final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
//                        Log.v(TAG, "updating time");

                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = INTERACTIVE_UPDATE_RATE_MS
                                    - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        private boolean shouldTimerBeRunning() {
            boolean timerNeeded = false;

            // run the timer if we're in ambient mode
            if(clockFace != null) {
                timerNeeded = clockFace.getAmbientMode();
            }

            // or run the timer if we're not showing the seconds hand
            if(clockState != null) {
                timerNeeded = timerNeeded || !clockState.getShowSeconds();
            }

            return timerNeeded;
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            Log.d(TAG, "updateTimer");

            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Callback from ClockState if something changes, which means we'll need
         * to redraw.
         * @param observable
         * @param data
         */
        @Override
        public void update(Observable observable, Object data) {
            updateTimer();
            invalidate();
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            Log.d(TAG, "onCreate");

            super.onCreate(holder);

            // announce our version number to the logs
            VersionWrapper.logVersion(CalWatchFaceService.this);

            setWatchFaceStyle(new WatchFaceStyle.Builder(CalWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setStatusBarGravity(Gravity.CENTER)
                    .setHotwordIndicatorGravity(Gravity.CENTER_HORIZONTAL) // I'd really like vertical to be 30% from top but can't say that here
                    .setViewProtection(WatchFaceStyle.PROTECT_HOTWORD_INDICATOR | WatchFaceStyle.PROTECT_STATUS_BAR)
                    .build());

            XWatchfaceReceiver.pingExternalStopwatches(CalWatchFaceService.this);
            BatteryWrapper.init(CalWatchFaceService.this);
            Resources resources = CalWatchFaceService.this.getResources();


            if (resources == null) {
                Log.e(TAG, "no resources? not good");
            }

            if(clockFace == null)
                clockFace = new ClockFace();

            clockState = ClockState.getSingleton();
            clockState.addObserver(this); // callbacks if something changes

            if(calendarFetcher != null)
                calendarFetcher.kill();

            calendarFetcher = new CalendarFetcher(CalWatchFaceService.this, WearableCalendarContract.Instances.CONTENT_URI, WearableCalendarContract.AUTHORITY);

            // start the background service, if it's not already running
            WearReceiverService.kickStart(CalWatchFaceService.this);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean lowBitAmbientMode = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            clockFace.setAmbientLowBit(lowBitAmbientMode);
            clockFace.setBurnInProtection(burnInProtection);
            Log.d(TAG, "onPropertiesChanged: low-bit ambient = " + lowBitAmbientMode + ", burn-in protection = " + burnInProtection);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
//            Log.d(TAG, "onTimeTick: ambient = " + isInAmbientMode());

            // this happens exactly once per minute; we're redrawing more often than that,
            // regardless, but this also provides a backstop if something is busted or buggy,
            // so we'll keep it in.
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            Log.d(TAG, "onAmbientModeChanged: " + inAmbientMode);
            clockFace.setAmbientMode(inAmbientMode);

            // If we just switched *to* ambient mode, then we've got some FPS data to report
            // to the logs. Otherwise, we're coming *back* from ambient mode, so it's a good
            // time to reset the counters.
            if(inAmbientMode)
                TimeWrapper.frameReport();
            else
                TimeWrapper.frameReset();

            invalidate();

            // Whether the timer should be running depends on whether we're in ambient mode (as well
            // as whether we're visible), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);
            clockFace.setMuteMode(interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE);
            invalidate();
        }

        private long drawCounter = 0;
        private int oldHeight, oldWidth;

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
//                Log.v(TAG, "onDraw");
            drawCounter++;

            int width = bounds.width();
            int height = bounds.height();

            if(width != oldWidth || height != oldHeight) {
                oldWidth = width;
                oldHeight = height;
                clockFace.setSize(width, height);
            }

            try {
                // clear the screen
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

                TimeWrapper.update(); // fetch the time
                clockFace.drawEverything(canvas);
            } catch (Throwable t) {
                if(drawCounter % 1000 == 0)
                    Log.e(TAG, "Something blew up while drawing", t);
            }

            // Draw every frame as long as we're visible and doing the sweeping second hand,
            // otherwise the timer will take care of it.
            if (isVisible() && !shouldTimerBeRunning())
                invalidate();
        }

        @Override
        public void onDestroy() {
            Log.v(TAG, "onDestroy");
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);

            if(calendarFetcher != null)
                calendarFetcher.kill();

            if(clockState != null) {
                clockState.deleteObserver(this);
                clockState = null;
            }

            if(clockFace != null) {
                clockFace.kill();
                clockFace = null;
            }

            super.onDestroy();
        }

        @Override
        public void onPeekCardPositionUpdate(Rect bounds) {
            super.onPeekCardPositionUpdate(bounds);
            Log.d(TAG, "onPeekCardPositionUpdate: " + bounds + " (" + bounds.width() + ", " + bounds.height() + ")");

            clockFace.setPeekCardRect(bounds);

            invalidate();
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            boolean isRound = insets.isRound();
            int chinSize = insets.getSystemWindowInsetBottom();

            Log.v(TAG, "onApplyWindowInsets (round: " + isRound + "), (chinSize: " + chinSize + ")");

            clockFace.setRound(isRound);
            clockFace.setMissingBottomPixels(chinSize);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);


            if (visible) {
                invalidate();
            }

            // If we just switched *to* not visible mode, then we've got some FPS data to report
            // to the logs. Otherwise, we're coming *back* from invisible mode, so it's a good
            // time to reset the counters.
            if(!visible)
                TimeWrapper.frameReport();
            else
                TimeWrapper.frameReset();

        }
    }
}
