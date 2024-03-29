package com.keyzane.twilio_voice;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

//import com.twilio.voice.Call;
import com.bumptech.glide.Glide;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.firebase.storage.FirebaseStorage;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;
import com.twilio.voice.CallInvite;

public class BackgroundCallJavaActivity extends AppCompatActivity {

    private static String TAG = "BackgroundCallActivity";
    public static final String TwilioPreferences = "com.keyzane.twilio_voicePreferences";


    //    private Call activeCall;
    private NotificationManager notificationManager;
    
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;

    private TextView tvUserName;
    private TextView tvCallStatus;
    private ImageView btnMute;
    private ImageView btnOutput;
    private ImageView btnHangUp;
    private ShapeableImageView cvImage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_background_call);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        tvUserName = (TextView) findViewById(R.id.tvUserName);
        tvCallStatus = (TextView) findViewById(R.id.tvCallStatus);
        btnMute = (ImageView) findViewById(R.id.btnMute);
        btnOutput = (ImageView) findViewById(R.id.btnOutput);
        btnHangUp = (ImageView) findViewById(R.id.btnHangUp);
        cvImage = (ShapeableImageView) findViewById(R.id.cvImage);


        KeyguardManager kgm = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        Boolean isKeyguardUp = kgm.inKeyguardRestrictedInputMode();

        Log.d(TAG, "isKeyguardUp $isKeyguardUp");
        if (isKeyguardUp) {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setTurnScreenOn(true);
                setShowWhenLocked(true);
                kgm.requestDismissKeyguard(this, null);

            } else {
                wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, TAG);
                wakeLock.acquire();

                getWindow().addFlags(
                        WindowManager.LayoutParams.FLAG_FULLSCREEN |
                                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                );
            }
        }

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        handleCallIntent(getIntent());
    }

    private void handleCallIntent(Intent intent) {
        if (intent != null) {

            
            if (intent.getStringExtra(Constants.CALL_FROM) != null) {
                activateSensor();
                String fromId = intent.getStringExtra(Constants.CALL_FROM).replace("client:", "");

                SharedPreferences preferences = getApplicationContext().getSharedPreferences(TwilioPreferences, Context.MODE_PRIVATE);
                //String caller = preferences.getString(fromId, preferences.getString("defaultCaller", getString(R.string.unknown_caller)));
                String caller = intent.getStringExtra(Constants.CALL_FROM_NAME);
                String callerImage = intent.getStringExtra(Constants.FROM_USER_IMAGE);
                Log.d(TAG, "handleCallIntent");
                Log.d(TAG, "caller from");
                Log.d(TAG, caller);
                tvUserName.setText(caller);
                tvCallStatus.setText(getString(R.string.connected_status));
                loadImageFromFirebase(callerImage, cvImage);
                Log.d(TAG, "handleCallIntent-");
                configCallUI();
            }else{
                finish();
            }
        }
    }

    private void activateSensor() {
        if (wakeLock == null) {
            Log.d(TAG, "New wakeLog");
            wakeLock = powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "keyzane:incall");
        }
        if (!wakeLock.isHeld()) {
            Log.d(TAG, "wakeLog acquire");
            wakeLock.acquire();
        } 
    }

    private void deactivateSensor() {
        if (wakeLock != null && wakeLock.isHeld()) {
            Log.d(TAG, "wakeLog release");
            wakeLock.release();
        } 
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getAction() != null) {
            Log.d(TAG, "onNewIntent-");
            Log.d(TAG, intent.getAction());
            switch (intent.getAction()) {
                case Constants.ACTION_CANCEL_CALL:
                    callCanceled();
                    break;
                default: {
                }
            }
        }
    }
    

    boolean isMuted = false;

    private void configCallUI() {
        Log.d(TAG, "configCallUI");

        btnMute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "onCLick");
                sendIntent(Constants.ACTION_TOGGLE_MUTE);
                isMuted = !isMuted;
                applyFabState(btnMute, isMuted);
            }
        });

        btnHangUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendIntent(Constants.ACTION_END_CALL);
                finish();

            }
        });
        btnOutput.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AudioManager audioManager = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
                boolean isOnSpeaker = !audioManager.isSpeakerphoneOn();
                audioManager.setSpeakerphoneOn(isOnSpeaker);
                applyFabState(btnOutput, isOnSpeaker);
            }
        });

    }

    private void applyFabState(ImageView button, Boolean enabled) {
        // Set fab as pressed when call is on hold

        ColorStateList colorStateList;

        if (enabled) {
            colorStateList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white_55));
        } else {
            colorStateList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setBackgroundTintList(colorStateList);
        }
    }

    private void sendIntent(String action) {
        Log.d(TAG, "Sending intent");
        Log.d(TAG, action);
        Intent activeCallIntent = new Intent();
        activeCallIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activeCallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activeCallIntent.setAction(action);
        LocalBroadcastManager.getInstance(this).sendBroadcast(activeCallIntent);
    }


    private void callCanceled() {
        finish();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        deactivateSensor();
    }

    public void loadImageFromFirebase(String imageUrl, ShapeableImageView imageView) {
        /*Picasso picassoInstance = new  Picasso.Builder(getBaseContext())
                .addRequestHandler(new FirebaseRequestHandler())
                .build();

        picassoInstance.load("gs://test-project-396e8.appspot.com/images/users/" + imageUrl)
                .fit().centerInside()
                .into(new Target() {
                    @Override
                    public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {

                    }

                    @Override
                    public void onBitmapFailed(Exception e, Drawable errorDrawable) {

                    }

                    @Override
                    public void onPrepareLoad(Drawable placeHolderDrawable) {

                    }
                });*/

        FirebaseStorage.getInstance().getReferenceFromUrl("gs://keyzane-prod.appspot.com/images/users/" + imageUrl)
                .getDownloadUrl().addOnSuccessListener(uri -> {
                    Glide.with(this).load(uri).into(imageView);
                });
    }
}