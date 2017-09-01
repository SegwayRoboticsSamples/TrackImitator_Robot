package com.segway.robot.TrackingSample_Robot;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;

import com.segway.robot.sdk.base.log.Logger;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

class MusicPlayer {
    private static final String TAG = "MusicPlayer";
    private static MusicPlayer mMusicPlayer = null;

    private final String mObstacleFile = "notify.wav";
    public static final int OBSTACLE_APPEARANCE = 1;
    private MediaPlayer mObstacleAppearancePlayer;
    private AtomicBoolean isObstacleSoundStarted = new AtomicBoolean();


    private MusicPlayHandler mMusicPlayHandler;
    private Context mContext;

    private class MusicPlayHandler extends Handler {
        MusicPlayHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            Logger.d(TAG, "touch event " + msg.what);
            switch (msg.what) {
                case OBSTACLE_APPEARANCE:
                    if (mObstacleAppearancePlayer == null) {
                        Logger.e(TAG, "Create side touch up media failed, media not play.");
                        break;
                    }
                    try {
                        internalPlay(mObstacleAppearancePlayer, isObstacleSoundStarted);
                    } catch (IOException e) {
                        Logger.e(TAG, "Play side touch up sound exception", e);
                    }
                    break;

                default:
                    break;
            }
        }
    }

    private MusicPlayer() {
        HandlerThread handlerThread = new HandlerThread("sound");
        handlerThread.start();
        mMusicPlayHandler = new MusicPlayHandler(handlerThread.getLooper());
    }

    static synchronized MusicPlayer getInstance() {
        if (mMusicPlayer == null) {
            mMusicPlayer = new MusicPlayer();
        }
        return mMusicPlayer;
    }

    void initialize(Context context) {
        mContext = context;
        mObstacleAppearancePlayer = createMediaPlayer(context, mObstacleFile);
    }

    private MediaPlayer createMediaPlayer(Context context, String mediaFileName) {
        AssetManager assetManager = context.getAssets();
        MediaPlayer mediaPlayer = new MediaPlayer();
        try {
            AssetFileDescriptor fileDescriptor = assetManager.openFd(mediaFileName);
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setDataSource(fileDescriptor.getFileDescriptor(), fileDescriptor.getStartOffset(), fileDescriptor.getLength());
            return mediaPlayer;
        } catch (IOException e) {
            Logger.e(TAG, "create media player error:", e);
        }
        return null;
    }

    private void internalPlay(MediaPlayer mediaPlayer, AtomicBoolean flag) throws IOException {
        if (mediaPlayer.isPlaying() || flag.get()) {
            flag.set(false);
            mediaPlayer.stop();
        }
        try {
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    mp.start();
                }
            });
            flag.set(true);
        } catch (IllegalStateException e) {
            Logger.w(TAG, "MediaPlayer prepare exception", e);
            if (mContext != null) {
                release();
                initialize(mContext);
            }
        }
    }

    void playMusic(int event) {
        Logger.d(TAG, "play music for event: " + event);
        mMusicPlayHandler.sendEmptyMessage(event);
    }

    void release() {
        if (mObstacleAppearancePlayer != null) {
            mObstacleAppearancePlayer.reset();
            mObstacleAppearancePlayer.release();
            mObstacleAppearancePlayer = null;
        }
    }
}
