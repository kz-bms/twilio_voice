package com.keyzane.twilio_voice;

import android.annotation.TargetApi;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationManagerCompat;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;

import org.json.JSONException;
import org.json.JSONObject;

public class IncomingCallNotificationService extends Service {

    private static final String TAG = IncomingCallNotificationService.class.getSimpleName();
    public static final String TwilioPreferences = "com.keyzane.twilio_voicePreferences";
    public static final int missedCallNotificationId = 100;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        Log.i(TAG, "onStartCommand " + action);
        if (action != null) {
            CallInvite callInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
            int notificationId = intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0);
            switch (action) {
                case Constants.ACTION_INCOMING_CALL:
                    handleIncomingCall(callInvite, notificationId);
                    break;
                case Constants.ACTION_ACCEPT:
                    int origin = intent.getIntExtra(Constants.ACCEPT_CALL_ORIGIN, 0);
                    Log.d(TAG, "onStartCommand-ActionAccept" + origin);
                    accept(callInvite, notificationId, origin);
                    break;
                case Constants.ACTION_REJECT:
                    reject(callInvite);
                    break;
                case Constants.ACTION_CANCEL_CALL:
                    handleCancelledCall(intent);
                    break;
                case Constants.ACTION_RETURN_CALL:
                    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(getApplicationContext());
                    notificationManager.cancel(missedCallNotificationId);
                    returnCall(intent);
                    break;
                default:
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification(CallInvite callInvite, int notificationId, int channelImportance) {
        Log.i(TAG, "createNotification");
        Intent intent = getPackageManager().getLaunchIntentForPackage(getApplicationContext().getPackageName());

        Log.e(TAG, TwilioVoicePlugin.getAppState() + "||" + TwilioVoicePlugin.hasStarted);

        if (TwilioVoicePlugin.getAppState().isAtLeast(Lifecycle.State.STARTED) || (TwilioVoicePlugin.getAppState().isAtLeast(Lifecycle.State.CREATED) && TwilioVoicePlugin.hasStarted)) {
            intent = new Intent(this, AnswerJavaActivity.class);
        }

        intent.setAction(Constants.ACTION_INCOMING_CALL_NOTIFICATION);
        intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        intent.putExtra(Constants.OPEN_ANSWER_ACTIVITY, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            pendingIntent = PendingIntent.getActivity(this, notificationId, intent, PendingIntent.FLAG_MUTABLE);
        } else {
            pendingIntent = PendingIntent.getActivity(this, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        }

        /*
         * Pass the notification id and call sid to use as an identifier to cancel the
         * notification later
         */
        Bundle extras = new Bundle();
        extras.putString(Constants.CALL_SID_KEY, callInvite.getCallSid());

        Context context = getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences(TwilioPreferences, Context.MODE_PRIVATE);
        Log.i(TAG, "Setting notification from, " + callInvite.getFrom());
        String fromId = callInvite.getFrom().replace("client:", "");
        //String caller = preferences.getString(fromId, preferences.getString("defaultCaller", "Unknown caller"));
        String caller = callInvite.getCustomParameters().get("callFromUser");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.i(TAG, "building notification for new phones");
            return buildNotification(getApplicationName(context), getString(R.string.new_call, caller),
                    pendingIntent,
                    extras,
                    callInvite,
                    notificationId,
                    createChannel(channelImportance));
        } else {
            Log.i(TAG, "building notification for older phones");

            return new NotificationCompat.Builder(this, createChannel(channelImportance))
                    .setSmallIcon(R.drawable.ic_call_end_white_24dp)
                    .setContentTitle(getApplicationName(context))
                    .setContentText(getString(R.string.new_call, caller))
                    .setAutoCancel(true)
                    .setOngoing(true)
                    .setExtras(extras)
                    .setContentIntent(pendingIntent)
                    .setFullScreenIntent(pendingIntent, true)
                    .setVibrate(new long[]{1000, 1000, 1000, 1000, 1000, 1000, 1000})
                    .setLights(Color.RED, 3000, 3000)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setColor(Color.rgb(20, 10, 200)).build();
        }
    }

    public static String getApplicationName(Context context) {
        ApplicationInfo applicationInfo = context.getApplicationInfo();
        int stringId = applicationInfo.labelRes;
        return stringId == 0 ? applicationInfo.nonLocalizedLabel.toString() : context.getString(stringId);
    }

    /**
     * Build a notification.
     *
     * @param text          the text of the notification
     * @param pendingIntent the body, pending intent for the notification
     * @param extras        extras passed with the notification
     * @return the builder
     */
    @TargetApi(Build.VERSION_CODES.O)
    private Notification buildNotification(String title, String text, PendingIntent pendingIntent, Bundle extras,
                                           final CallInvite callInvite,
                                           int notificationId,
                                           String channelId) {
        Log.d(TAG, "Building notification");
        Intent rejectIntent = new Intent(getApplicationContext(), IncomingCallNotificationService.class);
        rejectIntent.setAction(Constants.ACTION_REJECT);
        rejectIntent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        rejectIntent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        PendingIntent piRejectIntent = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            piRejectIntent = PendingIntent.getService(getApplicationContext(), 0, rejectIntent, PendingIntent.FLAG_MUTABLE);
        } else {
            piRejectIntent = PendingIntent.getService(getApplicationContext(), 0, rejectIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        }

        Intent acceptIntent = new Intent(getApplicationContext(), AnswerJavaActivity.class);
        acceptIntent.setAction(Constants.ACTION_ACCEPT);
        acceptIntent.putExtra(Constants.ACCEPT_CALL_ORIGIN, 0);
        acceptIntent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        acceptIntent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        PendingIntent piAcceptIntent = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            piAcceptIntent = PendingIntent.getActivity(getApplicationContext(), 0, acceptIntent, PendingIntent.FLAG_MUTABLE);
        } else {
            piAcceptIntent = PendingIntent.getService(getApplicationContext(), 0, acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        }

        long[] mVibratePattern = new long[]{0, 400, 400, 400, 400, 400, 400, 400};
        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), channelId)
                .setSmallIcon(R.drawable.ic_call_end_white_24dp)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_CALL)
                .setContentIntent(pendingIntent)
                .setExtras(extras)
                .setVibrate(mVibratePattern)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)/*
                .addAction(android.R.drawable.ic_menu_delete, getString(R.string.decline), piRejectIntent)
                .addAction(android.R.drawable.ic_menu_call, getString(R.string.answer), piAcceptIntent)*/
                .setFullScreenIntent(pendingIntent, true);

        return builder.build();
    }

    @TargetApi(Build.VERSION_CODES.O)
    private String createChannel(int channelImportance) {
        Log.i(TAG, "creating channel!");
        NotificationChannel callInviteChannel = new NotificationChannel(Constants.VOICE_CHANNEL_HIGH_IMPORTANCE,
                "Primary Voice Channel", NotificationManager.IMPORTANCE_HIGH);
        String channelId = Constants.VOICE_CHANNEL_HIGH_IMPORTANCE;

        if (channelImportance == NotificationManager.IMPORTANCE_LOW) {
            Log.i(TAG, "channel is low importance");
            callInviteChannel = new NotificationChannel(Constants.VOICE_CHANNEL_LOW_IMPORTANCE,
                    "Primary Voice Channel", NotificationManager.IMPORTANCE_LOW);
            channelId = Constants.VOICE_CHANNEL_LOW_IMPORTANCE;
        }
        callInviteChannel.setLightColor(Color.GREEN);
        callInviteChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(callInviteChannel);

        return channelId;
    }

    private void accept(CallInvite callInvite, int notificationId, int origin) {
        endForeground();
        Log.i(TAG, "accept call invite!");
        SoundPoolManager.getInstance(this).stopRinging();

        Intent activeCallIntent;
        if (origin == 0 && !TwilioVoicePlugin.isAppVisible()) {
            Log.i(TAG, "Creating answerJavaActivity intent");
            activeCallIntent = new Intent(this, AnswerJavaActivity.class);
        } else {
            Log.i(TAG, "Creating answer broadcast intent");
            activeCallIntent = new Intent();
        }

        activeCallIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activeCallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activeCallIntent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        activeCallIntent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        activeCallIntent.putExtra(Constants.ACCEPT_CALL_ORIGIN, origin);
        activeCallIntent.setAction(Constants.ACTION_ACCEPT);
        if (origin == 0 && !TwilioVoicePlugin.isAppVisible()) {
            startActivity(activeCallIntent);
            Log.i(TAG, "starting activity");
        } else {
            LocalBroadcastManager.getInstance(this).sendBroadcast(activeCallIntent);
            Log.i(TAG, "sending broadcast intent");
        }
    }

    private void reject(CallInvite callInvite) {
        callInvite.reject(getApplicationContext());
        SoundPoolManager.getInstance(this).stopRinging();
        SoundPoolManager.getInstance(this).playDisconnect();
        Intent rejectCallIntent = new Intent();
        rejectCallIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        rejectCallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        rejectCallIntent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        rejectCallIntent.setAction(Constants.ACTION_REJECT);
        LocalBroadcastManager.getInstance(this).sendBroadcast(rejectCallIntent);
        endForeground();
    }

    private void handleCancelledCall(Intent intent) {
        SoundPoolManager.getInstance(this).stopRinging();
        CancelledCallInvite cancelledCallInvite = intent.getParcelableExtra(Constants.CANCELLED_CALL_INVITE);
        SharedPreferences preferences = getApplicationContext().getSharedPreferences(TwilioPreferences, Context.MODE_PRIVATE);
        boolean prefsShow = preferences.getBoolean("show-notifications", true);
        if (prefsShow) {
            String callerName = cancelledCallInvite.getCustomParameters().get("callFromUser");
            String receiverName = cancelledCallInvite.getCustomParameters().get("callToUser");

            JSONObject callPayload = new JSONObject();
            try{
                callPayload.put("type", "missedAudioCall");
                callPayload.put("kzId", cancelledCallInvite.getFrom().replace("client:", "").trim());
                callPayload.put("name", callerName);
                callPayload.put("imageUrl", cancelledCallInvite.getCustomParameters().get("fromUserImage"));
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }

            buildMissedCallNotification(cancelledCallInvite.getFrom(), cancelledCallInvite.getTo(), callerName, receiverName, callPayload);
        }
        endForeground();
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void returnCall(Intent intent) {
        endForeground();
        Log.i(TAG, "returning call!!!!");
        Log.i(TAG, intent.getStringExtra(Constants.CALL_FROM));
        Log.i(TAG, intent.getStringExtra(Constants.CALL_TO));
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.cancel(100);
    }


    private void buildMissedCallNotification(String callerId, String receicerId, String callerName, String receiverName, JSONObject payload) {

        String fromId = callerId.replace("client:", "");
        Context context = getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences(TwilioPreferences, Context.MODE_PRIVATE);
        String title = getString(R.string.notification_missed_call, callerName);
        Intent  returnCallIntent ;

        PendingIntent piReturnCallIntent = null;

        if(TwilioVoicePlugin.isAppVisible()){
            Log.d(TAG, "Returning call with intent");
              returnCallIntent = new Intent(getApplicationContext(), IncomingCallNotificationService.class);
            returnCallIntent.setAction(Constants.ACTION_RETURN_CALL);
            returnCallIntent.putExtra(Constants.CALL_TO, receicerId);
            returnCallIntent.putExtra(Constants.CALL_FROM, callerId);
            returnCallIntent.putExtra(Constants.CALL_TO_NAME, receiverName);
            returnCallIntent.putExtra(Constants.CALL_FROM_NAME, callerName);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                piReturnCallIntent = PendingIntent.getService(getApplicationContext(), 0, returnCallIntent, PendingIntent.FLAG_MUTABLE);
            } else {
                piReturnCallIntent = PendingIntent.getService(getApplicationContext(), 0, returnCallIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            }
        }
        else {
            Log.d(TAG, "Returning call with launch intent");
            returnCallIntent = getPackageManager().getLaunchIntentForPackage(getApplicationContext().getPackageName());
            returnCallIntent.setAction(Constants.ACTION_RETURN_CALL);
            returnCallIntent.putExtra(Constants.CALL_TO, receicerId);
            returnCallIntent.putExtra(Constants.CALL_FROM, callerId);
            returnCallIntent.putExtra(Constants.CALL_TO_NAME, receiverName);
            returnCallIntent.putExtra(Constants.CALL_FROM_NAME, callerName);
            returnCallIntent.putExtra("payload", String.valueOf(payload));

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                piReturnCallIntent = PendingIntent.getActivity(getApplicationContext(), 0, returnCallIntent, PendingIntent.FLAG_MUTABLE);
            } else {
                piReturnCallIntent = PendingIntent.getActivity(getApplicationContext(), 0, returnCallIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            }

        }

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(this, createChannel(NotificationManager.IMPORTANCE_HIGH))
                            .setSmallIcon(R.drawable.notification_icon)
                            .setContentTitle(title)
                            .setCategory(Notification.CATEGORY_MESSAGE)
                            .setAutoCancel(true)
                            .addAction(android.R.drawable.ic_menu_call, getString(R.string.twilio_call_back), piReturnCallIntent)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setContentTitle(getApplicationName(context))
                            .setContentText(title)
                            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            notification = builder.build();
        } else {
            notification = new NotificationCompat.Builder(this, createChannel(NotificationManager.IMPORTANCE_HIGH))
                    .setSmallIcon(R.drawable.notification_icon)
                    .setContentTitle(getApplicationName(context))
                    .setContentText(title)
                    .setAutoCancel(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .addAction(android.R.drawable.ic_menu_call, getString(R.string.decline), piReturnCallIntent)
                    .setColor(Color.rgb(20, 10, 200)).build();
        }
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        if (ActivityCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
        notificationManager.notify(missedCallNotificationId, notification);
    }

    private void handleIncomingCall(CallInvite callInvite, int notificationId) {
        Log.i(TAG, "handle incoming call");
        SoundPoolManager.getInstance(this).playRinging();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setCallInProgressNotification(callInvite, notificationId);
        }
        sendCallInviteToActivity(callInvite, notificationId);
    }

    private void endForeground() {
        stopForeground(true);
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void setCallInProgressNotification(CallInvite callInvite, int notificationId) {
        if (TwilioVoicePlugin.isAppVisible()) {
            Log.i(TAG, "setCallInProgressNotification - app is visible.");
            startForeground(notificationId, createNotification(callInvite, notificationId, NotificationManager.IMPORTANCE_LOW));
        } else {
            Log.i(TAG, "setCallInProgressNotification - app is NOT visible.");
            startForeground(notificationId, createNotification(callInvite, notificationId, NotificationManager.IMPORTANCE_HIGH));
        }
    }

    /*
     * Send the CallInvite to the VoiceActivity. Start the activity if it is not running already.
     */
    private void sendCallInviteToActivity(CallInvite callInvite, int notificationId) {


        Log.i(TAG, "sendCallInviteToActivity.");

        Intent pluginIntent = new Intent();
        pluginIntent.setAction(Constants.ACTION_INCOMING_CALL);
        pluginIntent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        pluginIntent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        LocalBroadcastManager.getInstance(this).sendBroadcast(pluginIntent);
    }
}
