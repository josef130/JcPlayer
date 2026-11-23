package com.example.jean.jcplayersample;

import static android.Manifest.permission.POST_NOTIFICATIONS;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.example.jean.jcplayer.JcPlayerManagerListener;
import com.example.jean.jcplayer.general.JcStatus;
import com.example.jean.jcplayer.general.errors.OnInvalidPathListener;
import com.example.jean.jcplayer.model.JcAudio;
import com.example.jean.jcplayer.view.JcPlayerView;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity
        implements OnInvalidPathListener, JcPlayerManagerListener {
    final int PERMISSION_REQUEST_CODE =112;

    private static final String TAG = MainActivity.class.getSimpleName();

    private JcPlayerView player;
    private RecyclerView recyclerView;
    private AudioAdapter audioAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        /** Android 13 POST_NOTIFICATIONS permission*/
        // https://stackoverflow.com/questions/73940694/android-13-not-asking-for-post-notifications-permission
        // https://developer.android.com/develop/ui/views/notifications/notification-permission
        // https://stackoverflow.com/questions/67045607/how-to-resolve-missing-pendingintent-mutability-flag-lint-warning-in-android-a
        if (Build.VERSION.SDK_INT > 32) {
            if (!shouldShowRequestPermissionRationale("112")){
                getNotificationPermission();
            }
        }

        recyclerView = findViewById(R.id.recyclerView);
        player = findViewById(R.id.jcplayer);

        ArrayList<JcAudio> jcAudios = new ArrayList<>();
        jcAudios.add(JcAudio.createFromRaw("JcPlayer 2.8.3 - Local music", R.raw.a_34));
        jcAudios.add(JcAudio.createFromRaw("JcPlayer 2.8.3 - Local MIDI", R.raw.a_203));
        player.initPlaylist(jcAudios, this);
        adapterSetup();
    }

    @Override
    protected void onStop() {
        super.onStop();
        player.createNotification();
    }

    protected void adapterSetup() {
        audioAdapter = new AudioAdapter(player.getMyPlaylist());
        audioAdapter.setOnItemClickListener(new AudioAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(int position) {
                player.playAudio(player.getMyPlaylist().get(position));
            }

            @Override
            public void onSongItemDeleteClicked(int position) {
                Toast.makeText(MainActivity.this, "Delete song at position " + position,
                        Toast.LENGTH_SHORT).show();
//                if(player.getCurrentPlayedAudio() != null) {
//                    Toast.makeText(MainActivity.this, "Current audio = " + player.getCurrentPlayedAudio().getPath(),
//                            Toast.LENGTH_SHORT).show();
//                }
                removeItem(position);
            }
        });
        LinearLayoutManager layoutManager = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(audioAdapter);

        ((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);

    }

    @Override
    public void onPause() {
        super.onPause();
        player.createNotification();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Playback is owned by the service; use the system Stop control to end it.
    }

    @Override
    public void onPathError(JcAudio jcAudio) {
        Toast.makeText(this, jcAudio.getPath() + " with problems", Toast.LENGTH_LONG).show();
//        player.removeAudio(jcAudio);
//        player.next();
    }


    @Override
    public void onPreparedAudio(JcStatus status) {

    }

    @Override
    public void onCompletedAudio() {

    }

    @Override
    public void onPaused(JcStatus status) {

    }

    @Override
    public void onContinueAudio(JcStatus status) {

    }

    @Override
    public void onPlaying(JcStatus status) {

    }

    @Override
    public void onTimeChanged(@NonNull JcStatus status) {
        updateProgress(status);
    }

    @Override
    public void onJcpError(@NonNull Throwable throwable) {
        Toast.makeText(this, throwable.getMessage(), Toast.LENGTH_LONG).show();
    }

    private void updateProgress(final JcStatus jcStatus) {
        Log.d(TAG, "Song duration = " + jcStatus.getDuration()
                + "\n song position = " + jcStatus.getCurrentPosition());

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // calculate progress
                float progress = (float) (jcStatus.getDuration() - jcStatus.getCurrentPosition())
                        / (float) jcStatus.getDuration();
                progress = 1.0f - progress;
                audioAdapter.updateProgress(jcStatus.getJcAudio(), progress);
            }
        });
    }

    private void removeItem(int position) {
        ((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(true);

        //        jcAudios.remove(position);
        player.removeAudio(player.getMyPlaylist().get(position));
        audioAdapter.notifyItemRemoved(position);

        ((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
    }

    @Override
    public void onStopped(JcStatus status) {

    }

    /** Code POST_NOTIFICATIONS permission*/
    public void getNotificationPermission(){
        try {
            if (Build.VERSION.SDK_INT > 32) {
                ActivityCompat.requestPermissions(this,
                        new String[]{POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE);
            }
        }catch (Exception e){

        }
    }
}
