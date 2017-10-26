package com.segway.robot.TrackingSample_Robot;

/**
 * Created by Yi.Zhang on 2017/04/26.
 */

import android.app.Activity;
import android.content.Context;
import android.graphics.PointF;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import com.segway.robot.algo.Pose2D;
import com.segway.robot.algo.minicontroller.CheckPoint;
import com.segway.robot.algo.minicontroller.CheckPointStateListener;
import com.segway.robot.algo.minicontroller.ObstacleStateChangedListener;
import com.segway.robot.sdk.base.bind.ServiceBinder;
import com.segway.robot.sdk.base.log.Logger;
import com.segway.robot.sdk.baseconnectivity.Message;
import com.segway.robot.sdk.baseconnectivity.MessageConnection;
import com.segway.robot.sdk.baseconnectivity.MessageRouter;
import com.segway.robot.sdk.connectivity.BufferMessage;
import com.segway.robot.sdk.connectivity.RobotException;
import com.segway.robot.sdk.connectivity.RobotMessageRouter;
import com.segway.robot.sdk.emoji.Emoji;
import com.segway.robot.sdk.emoji.EmojiPlayListener;
import com.segway.robot.sdk.emoji.EmojiView;
import com.segway.robot.sdk.emoji.configure.BehaviorList;
import com.segway.robot.sdk.emoji.exception.EmojiException;
import com.segway.robot.sdk.emoji.player.RobotAnimator;
import com.segway.robot.sdk.emoji.player.RobotAnimatorFactory;
import com.segway.robot.sdk.locomotion.sbv.Base;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedList;

public class MainActivity extends Activity {
    private final String TAG = "TrackingActivity_Robot";

    private static final int ACTION_SHOW_MSG = 1;
    private static final int ACTION_BEHAVE = 2;
    private static final int ACTION_DOWNLOAD_AND_TRACK = 3;
    private static final int ACTION_TELL_PHONE = 4;

    private Context mContext;
    private TextView mTextView;
    private RobotMessageRouter mRobotMessageRouter = null;
    private MessageConnection mMessageConnection = null;
    private Boolean isTracking = false;

    private static LinkedList<PointF> mTrackingPoints;
    private Base mBase;
    private Emoji mEmoji;
    MusicPlayer mMusicPlayer;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(android.os.Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case ACTION_SHOW_MSG:
                    mTextView.setText(msg.obj.toString());
                    break;
                case ACTION_BEHAVE:
                    try {
                        mEmoji.startAnimation(RobotAnimatorFactory.getReadyRobotAnimator((Integer)msg.obj), new EmojiPlayListener() {
                            @Override
                            public void onAnimationStart(RobotAnimator animator) {
                                Log.d(TAG, "onAnimationStart: " + animator);
                            }

                            @Override
                            public void onAnimationEnd(RobotAnimator animator) {
                                Log.d(TAG, "onAnimationEnd: " + animator);
                            }

                            @Override
                            public void onAnimationCancel(RobotAnimator animator) {
                                Log.d(TAG, "onAnimationCancel: " + animator);
                            }
                        });
                    } catch (EmojiException e) {
                        Log.e(TAG, "onCreate: ", e);
                    }
                    break;
                case ACTION_DOWNLOAD_AND_TRACK:
                    Message message = (Message)msg.obj;
                    byte[] bytes = (byte[]) message.getContent();

                    // determin STOP message or PointsList message
                    if(bytes.length == 4){
                        ByteBuffer buffer = ByteBuffer.wrap(bytes);
                        if(buffer.getInt() == 0) {
                            Log.d(TAG, "Received STOP message");
                            stopTracking();
                        }
                    } else {
                        // if robot is NOT tracking, start tracking, else ignored the tracking data
                        if(!isTracking) {
                            // convert raw data(byte[]) into mTrackingPoints(LinkedList), mTrackingPoints is global variable
                            downLoadData(bytes);
                            startTracking();

                            // tell phone that tracking has successfully started
                            android.os.Message mMessage = mHandler.obtainMessage(ACTION_TELL_PHONE, 0);
                            mHandler.sendMessage(mMessage);
                        } else {
                            // tell phone that tracking data had been ignored
                            android.os.Message mMessage = mHandler.obtainMessage(ACTION_TELL_PHONE, 1);
                            mHandler.sendMessage(mMessage);
                        }
                    }
                    break;
                case ACTION_TELL_PHONE:
                    if (mMessageConnection != null) {
                        int dataIgnored = (int) msg.obj;
                        if (0 == dataIgnored) {
                            // tell phone that tracking has successfully started
                            byte[] messageByte = packMessage(false);
                            try {
                                mMessageConnection.sendMessage(new BufferMessage(messageByte));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        } else if (1 == dataIgnored) {
                            // tell phone that tracking data had been ignored
                            byte[] messageByte = packMessage(true);
                            try {
                                mMessageConnection.sendMessage(new BufferMessage(messageByte));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                    break;
            }
        }
    };

    // called when service bind or unBind, register MessageConnectionListener
    private ServiceBinder.BindStateListener mBindStateListener = new ServiceBinder.BindStateListener() {
        @Override
        public void onBind() {
            Log.d(TAG, "onBind");
            Toast.makeText(mContext, "Service bind success", Toast.LENGTH_SHORT).show();
            try {
                //register MessageConnectionListener in the RobotMessageRouter
                mRobotMessageRouter.register(mMessageConnectionListener);
            } catch (RobotException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onUnbind(String reason) {
            Log.e(TAG, "onUnbind: " + reason);
            Toast.makeText(mContext, "Service bind FAILED", Toast.LENGTH_SHORT).show();
        }
    };

    // called when connection created, set ConnectionStateListener and MessageListener in onConnectionCreated
    private MessageRouter.MessageConnectionListener mMessageConnectionListener = new RobotMessageRouter.MessageConnectionListener() {
        @Override
        public void onConnectionCreated(final MessageConnection connection) {
            Log.d(TAG, "onConnectionCreated: " + connection.getName());
            mMessageConnection = connection;
            try {
                mMessageConnection.setListeners(mConnectionStateListener, mMessageListener);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    };

    // called when connection state changed
    private MessageConnection.ConnectionStateListener mConnectionStateListener = new MessageConnection.ConnectionStateListener() {
        @Override
        public void onOpened() {
            Log.d(TAG, "onOpened: ");
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "connected to: " + mMessageConnection.getName(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onClosed(String error) {
            Log.e(TAG, "onClosed: " + error);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "disconnected to: " + mMessageConnection.getName(), Toast.LENGTH_SHORT).show();
                }
            });
        }
    };

    // called when message received/sent/sentError, download data in onMessageReceived
    private MessageConnection.MessageListener mMessageListener = new MessageConnection.MessageListener() {
        @Override
        public void onMessageSentError(Message message, String error) {
            Log.d(TAG, "Message send error");
        }

        @Override
        public void onMessageSent(Message message) {
            Log.d(TAG, "Message sent");
        }

        @Override
        public void onMessageReceived(final Message message) {
            Log.d(TAG, "onMessageReceived: id=" + message.getId() + ";timestamp=" + message.getTimestamp());
            if (message instanceof BufferMessage) {
                // don't do too much work here to avoid blockage of next message
                // download data and start tracking in UIThread
                android.os.Message msg = mHandler.obtainMessage(ACTION_DOWNLOAD_AND_TRACK, message);
                mHandler.sendMessage(msg);
            } else {
                Log.e(TAG, "Received StringMessage. " + "It's not gonna happen");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mContext = this;

        initView();
        initConnection();
        initBase();
        mMusicPlayer = MusicPlayer.getInstance();
        mMusicPlayer.initialize(this);
    }


    @Override
    protected void onPause() {
        super.onPause();
        // Unbind base service and connection service
        mBase.unbindService();
        mRobotMessageRouter.unbindService();
        finish();
    }

    private void initView() {
        mTextView = (TextView) findViewById(R.id.tvHint);
        mEmoji = Emoji.getInstance();
        mEmoji.init(this);
        mEmoji.setEmojiView((EmojiView) findViewById(R.id.face));

        mTextView.setText(getDeviceIp());
    }

    private void initConnection() {
        // get RobotMessageRouter
        mRobotMessageRouter = RobotMessageRouter.getInstance();
        // bind to connection service in robot
        mRobotMessageRouter.bindService(this, mBindStateListener);
    }

    private void initBase() {
        // get Base Instance
        mBase = Base.getInstance();
        // bindService, if not, all Base api will not work.
        mBase.bindService(getApplicationContext(), new ServiceBinder.BindStateListener() {
            @Override
            public void onBind() {
                Log.d(TAG, "Base bind success");
                mBase.setControlMode(Base.CONTROL_MODE_NAVIGATION);
            }

            @Override
            public void onUnbind(String reason) {
                Log.d(TAG, "Base bind failed");
            }
        });
    }

    private String getDeviceIp() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int ipAddress = wifiInfo.getIpAddress();
        String ip = (ipAddress & 0xFF) + "." +
                ((ipAddress >> 8) & 0xFF) + "." +
                ((ipAddress >> 16) & 0xFF) + "." +
                (ipAddress >> 24 & 0xFF);
        return ip;
    }

    // download points sequence into linkedlist
    private void downLoadData(byte[] bytes) {
        mTrackingPoints = new LinkedList<PointF>();

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.getInt();    // ignore indicator
        while(buffer.hasRemaining()) {
            try {
                float x = buffer.getFloat();
                float y = buffer.getFloat();
                Log.d(TAG, "Receive " + x + "< >" + y);
                mTrackingPoints.push(new PointF(-y, -x));    // NOTICE: bitmap coordinate and robot coordinate are different
            } catch(BufferUnderflowException ignored) {
                break;
            }
        }
    }

    // robot start tracking point by point
    private void startTracking() {
        if (0 == mTrackingPoints.size())
            return;

        isTracking = true;

        // set Emoji
        android.os.Message msg = mHandler.obtainMessage(ACTION_BEHAVE, BehaviorList.LOOK_CURIOUS);
        mHandler.sendMessage(msg);

        if(mBase.getControlMode() != Base.CONTROL_MODE_NAVIGATION)
            mBase.setControlMode(Base.CONTROL_MODE_NAVIGATION);
        mBase.cleanOriginalPoint();
        Pose2D pos = mBase.getOdometryPose(-1);
        mBase.setOriginalPoint(pos);
        mBase.setUltrasonicObstacleAvoidanceEnabled(true);
        mBase.setUltrasonicObstacleAvoidanceDistance(0.5f);
        mBase.setObstacleStateChangeListener(new ObstacleStateChangedListener() {
            @Override
            public void onObstacleStateChanged(int ObstacleAppearance) {
                if (ObstacleAppearance == ObstacleStateChangedListener.OBSTACLE_APPEARED) {
                    mMusicPlayer.playMusic(MusicPlayer.OBSTACLE_APPEARANCE);
                }
            }
        });
        // debug thread
        final Thread observer = new Thread("ob") {
            @Override
            public void run() {
                while (!interrupted()) {
                    Pose2D currentPose = mBase.getOdometryPose(System.currentTimeMillis());
                    Log.d(TAG, "observer: currentPose= " + currentPose);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        };

        // add OnCheckPointArrivedListener
        mBase.setOnCheckPointArrivedListener(new CheckPointStateListener() {
            @Override
            public void onCheckPointArrived(CheckPoint checkPoint, Pose2D realPose, boolean isLast) {
                Log.d(TAG, "Tracking ended");
                isTracking = false;

                android.os.Message msg = mHandler.obtainMessage(ACTION_BEHAVE, BehaviorList.BOOT_WAKEUP);
                mHandler.sendMessage(msg);

                Toast.makeText(mContext, "Tracking finished", Toast.LENGTH_SHORT).show();
                observer.interrupt();
            }

            @Override
            public void onCheckPointMiss(CheckPoint checkPoint, Pose2D realPose, boolean isLast, int reason) {
            }
        });

        // add all track point
        Iterator<PointF> iter = mTrackingPoints.descendingIterator();
        PointF originPoint = iter.next();
        PointF prePoint = originPoint;
        while(iter.hasNext()) {
            PointF nowPoint = iter.next();
            float x = nowPoint.x - originPoint.x;
            float y = nowPoint.y - originPoint.y;
            float angle = getAngle(nowPoint.x - prePoint.x, nowPoint.y - prePoint.y);
            //mBase.addCheckPoint(x, y, angle); // To be fixed: getAngle function not stable
            mBase.addCheckPoint(x, y);
            prePoint = nowPoint;
            Log.d(TAG, "startTracking: add check point: x=" + x + " y=" + y + " angle=" + angle);
        }

        observer.start();
    }

    private void stopTracking() {
        isTracking = false;
        mBase.clearCheckPointsAndStop();
        mBase.setUltrasonicObstacleAvoidanceEnabled(false);
    }

    // convert x-y coordinate into angle in radian
    private float getAngle(float x, float y) {
        float angle = (float)Math.atan2(y, x);

        return angle;
    }

    // pack file to byte[]
    private byte[] packMessage(boolean dataIgnored) {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(dataIgnored? 1:0);
        buffer.flip();
        byte[] messageByte = buffer.array();
        return messageByte;
    }

}
