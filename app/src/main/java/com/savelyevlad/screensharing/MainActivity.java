package com.savelyevlad.screensharing;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;
import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity {

    private Button button;
    private TextView textView;

    private MediaProjectionManager mediaProjectionManager;
    private static final int REQUEST_CODE_CAPTURE_PERM = 1234;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        button = findViewById(R.id.button);
        textView = findViewById(R.id.textView);

        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent permissionIntent = mediaProjectionManager.createScreenCaptureIntent();

        startActivityForResult(permissionIntent, REQUEST_CODE_CAPTURE_PERM);

        button.setOnClickListener((v) -> startRecording());
    }

    private MediaProjection mediaProjection;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if(REQUEST_CODE_CAPTURE_PERM == requestCode) {
            if(resultCode == RESULT_OK) {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, intent);
                startRecording();
            }
            else {
                textView.setText(textView.getText() + "user didn't give permission");
                // User did not give permission
            }
        }
    }

    private static final String VIDEO_MINE_TYPE = "video/avc";
    private static final int VIDEO_WIDTH = 1280;
    private static final int VIDEO_HEIGHT = 720;

    private boolean muxerStarted = false;
    private Surface inputSurfave;
    private MediaMuxer muxer;
    private MediaCodec videoEncoder;
    private MediaCodec.BufferInfo videoBufferInfo;
    private int trackIndex = -1;

    private final Handler drainHandler = new Handler(Looper.getMainLooper());
    private Runnable drainEncoderRunnable = this::drainEncoder;

    @SuppressLint({"SdCardPath", "SetTextI18n"})
    private void startRecording() {
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);

        if (defaultDisplay == null) {
            throw new RuntimeException("No display found");
        }

        prepareVideoEncoder();

        try {
            muxer = new MediaMuxer(Environment.getExternalStorageDirectory().getAbsolutePath() + "/video.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException e) {
            textView.setText("wtf why is it here " + (muxer == null) + "\n");
            textView.setText(textView.getText() + " " + Environment.getExternalStorageDirectory().getAbsolutePath() + "/sdcard/video.mp4");
            textView.setText(textView.getText() + "\n" + e.getMessage());
            e.printStackTrace();
//            throw new RuntimeException("MediaMuxer creation failed", e);
        }

        if(true) {
            return;
        }

        // get display size and destiny
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int screenWidth = displayMetrics.widthPixels;
        int screenHeight = displayMetrics.heightPixels;
        int screenDestiny = displayMetrics.densityDpi;

        mediaProjection.createVirtualDisplay("Recording Display", screenWidth, screenHeight, screenDestiny,
                0, inputSurfave, null, null);

        drainEncoder();
    }

    private void prepareVideoEncoder() {
        videoBufferInfo = new MediaCodec.BufferInfo();
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MINE_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT);
        int frameRate = 30; // 30 fps

        // Some difficult but important shit
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000); // 6 Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / frameRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 second between I-frames

        try {
            videoEncoder = MediaCodec.createEncoderByType(VIDEO_MINE_TYPE);
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurfave = videoEncoder.createInputSurface();
            videoEncoder.start();
        } catch (IOException e) {
            releaseEncoders();
        }
    }

    private boolean drainEncoder() {
        drainHandler.removeCallbacks(drainEncoderRunnable);
        while(true) {
            int bufferIndex = videoEncoder.dequeueOutputBuffer(videoBufferInfo, 0);

            if (bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // nothing available yet
                break;
            }
            if(bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if(trackIndex >= 0) {
                    // honestly I don't understand this
                    throw new RuntimeException("format charged twice");
                }
                trackIndex = muxer.addTrack(videoEncoder.getOutputFormat());
                if(!muxerStarted && trackIndex >= 0) {
                    muxer.start();
                    muxerStarted = true;
                }
            }
            else if(bufferIndex < 0) {
                // not sure what's going on, ignore it (ORIGINAL COMMENT)
            }
            else {
                ByteBuffer encoderData = videoEncoder.getOutputBuffer(bufferIndex);
                if(encoderData == null) {
                    throw new RuntimeException("couldn't fetch buffer at index " + bufferIndex);
                }

                if((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    videoBufferInfo.size = 0;
                }

                if(videoBufferInfo.size != 0) {
                    if(muxerStarted) {
                        encoderData.position(videoBufferInfo.offset);
                        encoderData.limit(videoBufferInfo.offset + videoBufferInfo.size);
                        muxer.writeSampleData(trackIndex, encoderData, videoBufferInfo);
                    }
                    else {
                        // muxer not started
                    }
                }

                videoEncoder.releaseOutputBuffer(bufferIndex, false);

                if((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }

        drainHandler.postDelayed(drainEncoderRunnable, 10);
        return false;
    }

    private void releaseEncoders() {
        drainHandler.removeCallbacks(drainEncoderRunnable);
        if(muxer != null) {
            if(muxerStarted) {
                muxer.stop();
            }
            muxer.release();
            muxer = null;
            muxerStarted = false;
        }

        if(videoEncoder != null) {
            videoEncoder.stop();
            videoEncoder.release();
            videoEncoder = null;
        }

        if(inputSurfave != null) {
            inputSurfave.release();
            inputSurfave = null;
        }

        if(mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }

        videoBufferInfo = null;
        drainEncoderRunnable = null;
        trackIndex = -1;
    }
}