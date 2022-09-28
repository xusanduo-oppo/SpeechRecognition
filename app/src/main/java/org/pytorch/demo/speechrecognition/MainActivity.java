package org.pytorch.demo.speechrecognition;

import android.Manifest;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.pytorch.IValue;
import org.pytorch.Module;
import org.pytorch.Tensor;
//import org.pytorch.slice;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import org.pytorch.LiteModuleLoader;


public class MainActivity extends AppCompatActivity implements Runnable {
    private static final String TAG = MainActivity.class.getName();

    private Module module;
    private TextView mTextView;
    private Button mButton;

    private final static int ENH_CHUNK = 64000;
    private final static int HOP_CHUNK = 64000-256;
    private final static int REQUEST_RECORD_AUDIO = 13;
    private final static int AUDIO_LEN_IN_SECOND = 20;
    private final static int SAMPLE_RATE = 16000;
    private final static int RECORDING_LENGTH = SAMPLE_RATE * AUDIO_LEN_IN_SECOND;

    private final static String LOG_TAG = MainActivity.class.getSimpleName();

    private int mStart = 1;
    private HandlerThread mTimerThread;
    private Handler mTimerHandler;
    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            mTimerHandler.postDelayed(mRunnable, 1000);

            MainActivity.this.runOnUiThread(
                    () -> {
                        mButton.setText(String.format("Listening - %ds left", AUDIO_LEN_IN_SECOND - mStart));
                        mStart += 1;
                    });
        }
    };

    @Override
    protected void onDestroy() {
        stopTimerThread();
        super.onDestroy();
    }

    protected void stopTimerThread() {
        mTimerThread.quitSafely();
        try {
            mTimerThread.join();
            mTimerThread = null;
            mTimerHandler = null;
            mStart = 1;
        } catch (InterruptedException e) {
            Log.e(TAG, "Error on stopping background thread", e);
        }
    }

    private Context mContext;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mContext = this;
        mButton = findViewById(R.id.btnRecognize);
        mTextView = findViewById(R.id.tvResult);

        mButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mButton.setText(String.format("Listening - %ds left", AUDIO_LEN_IN_SECOND));
                mButton.setEnabled(false);

                Thread thread = new Thread(MainActivity.this);
                thread.start();

                mTimerThread = new HandlerThread("Timer");
                mTimerThread.start();
                mTimerHandler = new Handler(mTimerThread.getLooper());
                mTimerHandler.postDelayed(mRunnable, 1000);

            }
        });
        requestMicrophonePermission();
    }

    private void requestMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
//                    new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
                    new String[]{android.Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_RECORD_AUDIO);
        }
    }

    private String assetFilePath(Context context, String assetName) {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(assetName)) {
            try (OutputStream os = new FileOutputStream(file)) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
            return file.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, assetName + ": " + e.getLocalizedMessage());
        }
        return null;
    }

    private void showTranslationResult(String result) {
        mTextView.setText(result);
    }

    public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "Audio Record can't initialize!");
            return;
        }
        record.startRecording();

        long shortsRead = 0;
        int recordingOffset = 0;
        short[] audioBuffer = new short[bufferSize / 2];
        short[] recordingBuffer = new short[RECORDING_LENGTH];

        while (shortsRead < RECORDING_LENGTH) {
            int numberOfShort = record.read(audioBuffer, 0, audioBuffer.length);
            shortsRead += numberOfShort;
            System.arraycopy(audioBuffer, 0, recordingBuffer, recordingOffset, numberOfShort);
            recordingOffset += numberOfShort;
        }

        record.stop();
        record.release();
        stopTimerThread();

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mButton.setText("Recognizing...");
            }
        });

        float[] floatInputBuffer = new float[RECORDING_LENGTH];

        // feed in float values between -1.0f and 1.0f by dividing the signed 16-bit inputs.
        for (int i = 0; i < RECORDING_LENGTH; ++i) {
            floatInputBuffer[i] = recordingBuffer[i];// / (float) Short.MAX_VALUE;
        }
        byte[] tmp = new byte[RECORDING_LENGTH * 2];
        ByteBuffer.wrap(tmp).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(recordingBuffer);
        File docPath = null;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
            docPath = mContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        }else {
            docPath = Environment.getExternalStorageDirectory();
        }
        try {
//            final File inFile = new File("/sdcard/" + "in" + "_music.pcm");
            final File inFile = new File(docPath,  "in" + "_music.pcm");
            FileOutputStream inStream = new FileOutputStream(inFile);
            inStream.write(tmp, 0, tmp.length);
        } catch (Exception e) {
            e.printStackTrace();
        }

//        final String result = recognize(floatInputBuffer);
        byte result[] = recognize(floatInputBuffer);
//        final String result = recognize(floatInputBuffer);
        try {
//            final File writeFile = new File("/sdcard/" + "test" + "_music.pcm");
            final File writeFile = new File(docPath ,"test" + "_music.pcm");
            FileOutputStream outputStream = new FileOutputStream(writeFile);
            outputStream.write(result, 0, result.length);
        } catch (Exception e) {
            e.printStackTrace();
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showTranslationResult("process end");
                mButton.setEnabled(true);
                mButton.setText("Start");
            }
        });
    }

    private byte[] recognize(float[] floatInputBuffer) {
        if (module == null) {
            module = LiteModuleLoader.load(assetFilePath(getApplicationContext(), "dcunet.ptl"));
        }
        short out[] = new short[RECORDING_LENGTH];
//        double wav2vecinput[] = new double[RECORDING_LENGTH];
//        for (int n = 0; n < RECORDING_LENGTH; n++)
//            wav2vecinput[n] = floatInputBuffer[n];

        long num_chunks = RECORDING_LENGTH / HOP_CHUNK + 1;
//        showTranslationResult("process end");

        float frame[] = new float[ENH_CHUNK];
        int c;
        for (c = 0; c < num_chunks * HOP_CHUNK; c += HOP_CHUNK) {
            if(c==0)
            {
                for (int i =0 ;i<ENH_CHUNK-HOP_CHUNK; ++i)
                {
                    frame[i] = 0;
                }
                for(int i=0; i<HOP_CHUNK; ++i)
                {
                    frame[i+ENH_CHUNK-HOP_CHUNK] = floatInputBuffer[i];
                }
            }
            else
            {
                long len_ = RECORDING_LENGTH-c+ENH_CHUNK-HOP_CHUNK;
                len_ = len_>ENH_CHUNK?ENH_CHUNK:len_;
                for(int i=0; i<len_; ++i)
                {
                    frame[i] = floatInputBuffer[c-(ENH_CHUNK-HOP_CHUNK)+i];
                }
            }
            int last = c+HOP_CHUNK-RECORDING_LENGTH;
            if (last>0) {
                for (int i =0 ;i<last; ++i)
                {
                    frame[i+RECORDING_LENGTH-c+ENH_CHUNK-HOP_CHUNK] = 0;
                }
            }
            FloatBuffer inTensorBuffer = Tensor.allocateFloatBuffer(ENH_CHUNK);
            for (float val : frame)
                inTensorBuffer.put(val);
            Tensor inTensor = Tensor.fromBlob(inTensorBuffer, new long[]{1, ENH_CHUNK});
            Tensor outTensor = module.forward(IValue.from(inTensor)).toTensor();
            float[] float_out = outTensor.getDataAsFloatArray();
            for(int i=0;i<HOP_CHUNK;++i)
            {
                if(c+i<RECORDING_LENGTH)
                {
                    out[c+i] = (short) (float_out[i+ENH_CHUNK-HOP_CHUNK]/10);
                }
                else
                {
                    break;
                }
            }
//            nnet_process_one_frame(pDcunet, frame, Outdata+c, atoi(argv[4]), gpu_id);
        }

//        FloatBuffer inTensorBuffer = Tensor.allocateFloatBuffer(RECORDING_LENGTH);
//        for (double val : wav2vecinput)
//            inTensorBuffer.put((float) val);
//
//        Tensor inTensor = Tensor.fromBlob(inTensorBuffer, new long[]{1, RECORDING_LENGTH});
//        Tensor outTensor = module.forward(IValue.from(inTensor)).toTensor();
////        Tensor outTensor = inTensor;//module.forward(IValue.from(inTensor)).toTensor();
////        final String result = module.forward(IValue.from(inTensor)).toStr();
//        float[] float_out = outTensor.getDataAsFloatArray();
//        byte[] data = new byte[bufferSizeInBytes];
//        short[] out = new short[RECORDING_LENGTH];
//        for (int n = 0; n < RECORDING_LENGTH; n++)
//            out[n] = (short) (float_out[n]);
        byte[] data = new byte[RECORDING_LENGTH * 2];
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(out);
//        byte data[] = (byte [])out;
        return data;
    }
}