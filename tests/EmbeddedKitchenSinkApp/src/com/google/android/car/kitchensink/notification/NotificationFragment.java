package com.google.android.car.kitchensink.notification;

import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;

import com.google.android.car.kitchensink.KitchenSinkActivity;
import com.google.android.car.kitchensink.R;

import java.util.HashMap;

/**
 * Test fragment that can send all sorts of notifications.
 */
public class NotificationFragment extends Fragment {
    private static final String IMPORTANCE_HIGH_ID = "importance_high";
    private static final String IMPORTANCE_DEFAULT_ID = "importance_default";
    private static final String IMPORTANCE_LOW_ID = "importance_low";
    private static final String IMPORTANCE_MIN_ID = "importance_min";
    private static final String IMPORTANCE_NONE_ID = "importance_none";
    private int mCurrentNotificationId = 0;
    private NotificationManager mManager;
    private Context mContext;
    private Handler mHandler = new Handler();
    private HashMap<Integer, Runnable> mUpdateRunnables = new HashMap<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mManager =
                (NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
        mContext = getActivity();

        mManager.createNotificationChannel(new NotificationChannel(
                IMPORTANCE_HIGH_ID, "Importance High", NotificationManager.IMPORTANCE_HIGH));

        mManager.createNotificationChannel(new NotificationChannel(
                IMPORTANCE_DEFAULT_ID,
                "Importance Default",
                NotificationManager.IMPORTANCE_DEFAULT));

        mManager.createNotificationChannel(new NotificationChannel(
                IMPORTANCE_LOW_ID, "Importance Low", NotificationManager.IMPORTANCE_LOW));

        mManager.createNotificationChannel(new NotificationChannel(
                IMPORTANCE_MIN_ID, "Importance Min", NotificationManager.IMPORTANCE_MIN));

        mManager.createNotificationChannel(new NotificationChannel(
                IMPORTANCE_NONE_ID, "Importance None", NotificationManager.IMPORTANCE_NONE));
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.notification_fragment, container, false);

        initCancelAllButton(view);
        initHeadsupAndUpdatesBotton(view);
        initImportanceDefaultButton(view);
        initImportanceLowButton(view);
        initImportanceMinButton(view);
        initOngoingButton(view);
        initMessagingStyleButton(view);
        initProgressButton(view);
        initCarCategoriesButton(view);

        return view;
    }

    private void initCancelAllButton(View view) {
        view.findViewById(R.id.cancel_all_button).setOnClickListener(v -> {
            for (Runnable runnable : mUpdateRunnables.values()) {
                mHandler.removeCallbacks(runnable);
            }
            mUpdateRunnables.clear();
            mManager.cancelAll();
        });
    }

    private void initHeadsupAndUpdatesBotton(View view) {
        int id = mCurrentNotificationId++;
        Intent mIntent = new Intent(getActivity(), KitchenSinkActivity.class);
        PendingIntent mPendingIntent = PendingIntent.getActivity(getActivity(), 0, mIntent, 0);

        Notification notification1 = new Notification
                .Builder(getActivity(), IMPORTANCE_HIGH_ID)
                .setContentTitle("Importance High")
                .setContentText("blah")
                .setSmallIcon(R.drawable.car_ic_mode)
                .addAction(
                        new Notification.Action.Builder(null, "Action1", mPendingIntent).build())
                .addAction(
                        new Notification.Action.Builder(null, "Action2", mPendingIntent).build())
                .addAction(
                        new Notification.Action.Builder(null, "Action3", mPendingIntent).build())
                .setColor(mContext.getColor(android.R.color.holo_red_light))
                .build();

        view.findViewById(R.id.importance_high_button).setOnClickListener(
                v -> mManager.notify(id, notification1)
        );

        Notification notification2 = new Notification
                .Builder(getActivity(), IMPORTANCE_HIGH_ID)
                .setContentTitle("This is an instant update")
                .setContentText("of the previous one with IMPORTANCE_HIGH")
                .setSmallIcon(R.drawable.car_ic_mode)
                .setColor(mContext.getColor(android.R.color.holo_red_light))
                .addAction(
                        new Notification.Action.Builder(null, "Action?", mPendingIntent).build())
                .build();
        view.findViewById(R.id.importance_high_button_2).setOnClickListener(
                v -> mManager.notify(id, notification2));

        Notification notification3 = new Notification
                .Builder(getActivity(), IMPORTANCE_DEFAULT_ID)
                .setContentTitle("This is an update")
                .setContentText("of the previous one with IMPORTANCE_DEFAULT")
                .setSmallIcon(R.drawable.car_ic_mode)
                .setColor(mContext.getColor(android.R.color.holo_red_light))
                .build();
        view.findViewById(R.id.importance_high_button_3).setOnClickListener(
                v -> mManager.notify(id, notification3));
    }

    private void initMessagingStyleButton(View view) {

        Intent intent = new Intent(getActivity(), KitchenSinkActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(getActivity(), 0, intent, 0);

        view.findViewById(R.id.category_message_button).setOnClickListener(v -> {

            RemoteInput remoteInput = new RemoteInput.Builder("voice reply").build();
            PendingIntent replyIntent = PendingIntent.getBroadcast(
                    view.getContext().getApplicationContext(),
                    12345,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);

            Person personJohn = new Person.Builder().setName("John Doe").build();
            Person personJane = new Person.Builder().setName("Jane Roe").build();
            Notification.MessagingStyle messagingStyle =
                    new Notification.MessagingStyle(personJohn)
                            .setConversationTitle("Whassup")
                            .addHistoricMessage(
                                    new Notification.MessagingStyle.Message(
                                            "historic message",
                                            System.currentTimeMillis() - 3600,
                                            personJohn))
                            .addMessage(new Notification.MessagingStyle.Message(
                                    "message", System.currentTimeMillis(), personJane));

            Notification notification = new Notification
                    .Builder(getActivity(), IMPORTANCE_HIGH_ID)
                    .setContentTitle("Message from someone")
                    .setContentText("hi")
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setSmallIcon(R.drawable.car_ic_mode)
                    .setStyle(messagingStyle)
                    .setAutoCancel(true)
                    .setColor(mContext.getColor(android.R.color.holo_green_light))
                    .addAction(
                            new Notification.Action.Builder(null, "read", pendingIntent).build())
                    .addAction(
                            new Notification.Action.Builder(null, "reply", replyIntent)
                                    .addRemoteInput(remoteInput).build())
                    .build();
            mManager.notify(mCurrentNotificationId++, notification);
        });

    }

    private void initCarCategoriesButton(View view) {
        view.findViewById(R.id.category_car_emergency_button).setOnClickListener(v -> {
            Notification notification = new Notification
                    .Builder(getActivity(), IMPORTANCE_DEFAULT_ID)
                    .setContentTitle("OMG")
                    .setContentText("This is of top importance")
                    .setCategory(Notification.CATEGORY_CAR_EMERGENCY)
                    .setSmallIcon(R.drawable.car_ic_mode)
                    .build();
            mManager.notify(mCurrentNotificationId++, notification);
        });

        view.findViewById(R.id.category_car_warning_button).setOnClickListener(v -> {

            Notification notification = new Notification
                    .Builder(getActivity(), IMPORTANCE_MIN_ID)
                    .setContentTitle("OMG -ish ")
                    .setContentText("This is of less importance but still")
                    .setCategory(Notification.CATEGORY_CAR_WARNING)
                    .setSmallIcon(R.drawable.car_ic_mode)
                    .build();
            mManager.notify(mCurrentNotificationId++, notification);
        });

        view.findViewById(R.id.category_car_info_button).setOnClickListener(v -> {
            Notification notification = new Notification
                    .Builder(getActivity(), IMPORTANCE_DEFAULT_ID)
                    .setContentTitle("Car information")
                    .setContentText("Oil change due")
                    .setCategory(Notification.CATEGORY_CAR_INFORMATION)
                    .setColor(mContext.getColor(android.R.color.holo_purple))
                    .setColorized(true)
                    .setSmallIcon(R.drawable.car_ic_mode)
                    .build();
            mManager.notify(mCurrentNotificationId++, notification);
        });

    }

    private void initProgressButton(View view) {
        view.findViewById(R.id.progress_button).setOnClickListener(v -> {
            int id = mCurrentNotificationId++;

            Notification notification = new Notification
                    .Builder(getActivity(), IMPORTANCE_DEFAULT_ID)
                    .setContentTitle("Progress")
                    .setProgress(100, 0, false)
                    .setContentInfo("0%")
                    .setSmallIcon(R.drawable.car_ic_mode)
                    .build();
            mManager.notify(id, notification);

            Runnable runnable = new Runnable() {
                int mProgress = 3;

                @Override
                public void run() {
                    Notification updateNotification = new Notification
                            .Builder(getActivity(), IMPORTANCE_DEFAULT_ID)
                            .setContentTitle("Progress")
                            .setProgress(100, mProgress, false)
                            .setContentInfo(mProgress + "%")
                            .setSmallIcon(R.drawable.car_ic_mode)
                            .build();
                    mManager.notify(id, updateNotification);
                    mProgress += 3;
                    mProgress %= 100;
                    mHandler.postDelayed(this, 1000);
                }
            };
            mUpdateRunnables.put(id, runnable);
            mHandler.post(runnable);
        });
    }

    private void initImportanceDefaultButton(View view) {
        view.findViewById(R.id.importance_default_button).setOnClickListener(v -> {
            Notification notification = new Notification
                    .Builder(getActivity(), IMPORTANCE_DEFAULT_ID)
                    .setContentTitle("Importance Default")
                    .setSmallIcon(R.drawable.car_ic_mode)
                    .build();
            mManager.notify(mCurrentNotificationId++, notification);
        });
    }

    private void initImportanceLowButton(View view) {
        view.findViewById(R.id.importance_low_button).setOnClickListener(v -> {

            Notification notification = new Notification.Builder(getActivity(), IMPORTANCE_LOW_ID)
                    .setContentTitle("Importance Low")
                    .setContentText("low low low")
                    .setSmallIcon(R.drawable.car_ic_mode)
                    .build();
            mManager.notify(mCurrentNotificationId++, notification);
        });
    }

    private void initImportanceMinButton(View view) {
        view.findViewById(R.id.importance_min_button).setOnClickListener(v -> {

            Notification notification = new Notification.Builder(getActivity(), IMPORTANCE_MIN_ID)
                    .setContentTitle("Importance Min")
                    .setContentText("min min min")
                    .setSmallIcon(R.drawable.car_ic_mode)
                    .build();
            mManager.notify(mCurrentNotificationId++, notification);
        });
    }

    private void initOngoingButton(View view) {
        view.findViewById(R.id.ongoing_button).setOnClickListener(v -> {

            Notification notification = new Notification
                    .Builder(getActivity(), IMPORTANCE_DEFAULT_ID)
                    .setContentTitle("Playing music or something")
                    .setSmallIcon(R.drawable.car_ic_mode)
                    .setOngoing(true)
                    .build();
            mManager.notify(mCurrentNotificationId++, notification);
        });
    }
}
