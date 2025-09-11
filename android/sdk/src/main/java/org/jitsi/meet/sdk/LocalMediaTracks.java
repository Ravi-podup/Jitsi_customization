package org.jitsi.meet.sdk;

import android.content.Context;
import android.content.ContentValues;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Environment;
import android.util.Log;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Locale;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

/**
 * Java utility to create and manage local microphone and camera WebRTC tracks.
 */
final class LocalMediaTracks {

    enum Facing { FRONT, BACK }

    private static LocalMediaTracks instance;

    static synchronized LocalMediaTracks getInstance() {
        if (instance == null) {
            instance = new LocalMediaTracks();
        }
        return instance;
    }

    private Context appContext;
    private EglBase eglBase;
    private PeerConnectionFactory peerConnectionFactory;

    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private String lastAudioTrackId;
    private boolean suspendedWebRtcAudioForRecording = false;

    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private VideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;

    // File saving
    private File audioFile;
    private File videoFile;
    private FileOutputStream audioOutputStream;
    private FileOutputStream videoOutputStream;
    private boolean isRecordingToFile = false;

    // Audio recording simulation
    private Thread audioRecordingThread;
    private volatile boolean isRecordingAudio = false;

    // MediaRecorder AAC (M4A) recording
    private MediaRecorder mediaRecorder;
    private String currentOutputPath;

    private static final String TAG = "LocalMediaTracks";

    private LocalMediaTracks() { }

    private File getMusicDir() {
        File base = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        File dir = new File(base, "JitsiRecordings");
        if (!dir.exists()) {
            // Ignore failure; will error later if unusable
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    synchronized void startAacRecording() {
        if (mediaRecorder != null) {
            Log.w(TAG, "MediaRecorder already active");
            return;
        }
        try {
            File dir = getMusicDir();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File out = new File(dir, "jitsi_audio_" + ts + ".mp4");
            currentOutputPath = out.getAbsolutePath();

            mediaRecorder = new MediaRecorder();
            // Suspend WebRTC mic so we can exclusively capture
            suspendWebRtcAudioIfActive();

            // Use MIC for widest device compatibility
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(128_000);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setAudioChannels(1);
            mediaRecorder.setOutputFile(currentOutputPath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            Log.i(TAG, "AAC-in-MP4 recording started: " + currentOutputPath);
        } catch (Throwable t) {
            Log.e(TAG, "startAacRecording failed", t);
            safeReleaseMediaRecorder();
            resumeWebRtcAudioIfSuspended();
            throw new RuntimeException(t);
        }
    }

    synchronized void stopAacRecordingAndPlay() {
        try {
            if (mediaRecorder != null) {
                try { mediaRecorder.stop(); } catch (Throwable ignored) {}
            }
        } finally {
            safeReleaseMediaRecorder();
        }

        // Resume WebRTC audio capture after recording ends
        resumeWebRtcAudioIfSuspended();

        if (currentOutputPath == null) {
            Log.w(TAG, "No output path to play");
            return;
        }

        // Make file visible in media DB and also copy to public Music for easier user access
        try {
            MediaScannerConnection.scanFile(
                    appContext,
                    new String[]{ currentOutputPath },
                    null,
                    null);
        } catch (Throwable t) {
            Log.w(TAG, "Media scan failed", t);
        }

        Uri publicUri = null;
        try {
            publicUri = saveToPublicMusic(currentOutputPath);
            if (publicUri != null) {
                Log.i(TAG, "Copied recording to public Music: " + publicUri);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Copy to public Music failed", t);
        }

        try {
            MediaPlayer mp = new MediaPlayer();
            // Prefer public URI if available
            if (publicUri != null) {
                mp.setDataSource(appContext, publicUri);
            } else {
                mp.setDataSource(currentOutputPath);
            }
            mp.setOnCompletionListener(player -> player.release());
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.prepareAsync();
            Log.i(TAG, "Auto-playing: " + (publicUri != null ? publicUri.toString() : currentOutputPath));
        } catch (Throwable t) {
            Log.e(TAG, "Auto play failed", t);
        }
    }

    private Uri saveToPublicMusic(String sourcePath) throws IOException {
        File src = new File(sourcePath);
        if (!src.exists() || src.length() == 0) {
            return null;
        }

        String fileName = src.getName();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/JitsiRecordings");
        }

        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        Uri item = appContext.getContentResolver().insert(collection, values);
        if (item == null) {
            return null;
        }

        try (FileInputStream in = new FileInputStream(src);
             OutputStream out = appContext.getContentResolver().openOutputStream(item)) {
            if (out == null) {
                return null;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }

        return item;
    }

    private void safeReleaseMediaRecorder() {
        if (mediaRecorder != null) {
            try { mediaRecorder.reset(); } catch (Throwable ignored) {}
            try { mediaRecorder.release(); } catch (Throwable ignored) {}
            mediaRecorder = null;
        }
    }

    private void startAudioRecordingSimulation() {
        if (audioRecordingThread != null) {
            return; // Already running
        }

        isRecordingAudio = true;
        audioRecordingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Audio recording simulation started");

                while (isRecordingAudio && audioOutputStream != null) {
                    try {
                        // Generate some audio-like data (sine wave simulation)
                        byte[] audioData = generateAudioData();
                        audioOutputStream.write(audioData);
                        audioOutputStream.flush();

                        Log.d(TAG, "Wrote " + audioData.length + " bytes of simulated audio data");

                        // Sleep for ~20ms to simulate 50Hz audio sampling
                        Thread.sleep(20);
                    } catch (IOException e) {
                        Log.e(TAG, "Error writing audio data", e);
                        break;
                    } catch (InterruptedException e) {
                        Log.i(TAG, "Audio recording simulation interrupted");
                        break;
                    }
                }

                Log.i(TAG, "Audio recording simulation stopped");
            }
        });

        audioRecordingThread.start();
    }

    private byte[] generateAudioData() {
        // Generate a simple sine wave as audio data
        int sampleRate = 44100;
        int duration = 20; // 20ms
        int samples = (sampleRate * duration) / 1000;
        byte[] audioData = new byte[samples * 2]; // 16-bit samples

        for (int i = 0; i < samples; i++) {
            // Generate a simple sine wave
            double time = (double) i / sampleRate;
            double frequency = 440.0; // A4 note
            double amplitude = 0.3;
            short sample = (short) (amplitude * Short.MAX_VALUE * Math.sin(2 * Math.PI * frequency * time));

            // Convert to little-endian bytes
            audioData[i * 2] = (byte) (sample & 0xFF);
            audioData[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        return audioData;
    }

    private void stopAudioRecordingSimulation() {
        isRecordingAudio = false;
        if (audioRecordingThread != null) {
            try {
                audioRecordingThread.interrupt();
                audioRecordingThread.join(1000); // Wait up to 1 second
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while stopping audio recording");
            }
            audioRecordingThread = null;
        }
    }

    synchronized void initialize(Context context) {
        if (peerConnectionFactory != null) {
            return;
        }
        appContext = context.getApplicationContext();

        PeerConnectionFactory.InitializationOptions initOptions =
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        eglBase = EglBase.create();

        DefaultVideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(
                eglBase.getEglBaseContext(), /* enableIntelVp8Encoder */ true, /* enableH264HighProfile */ true);
        DefaultVideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();
    }

    synchronized AudioTrack createAudioTrack(String trackId) {
        ensureInitialized();
        if (audioTrack != null) {
            return audioTrack;
        }
        MediaConstraints constraints = new MediaConstraints();
        audioSource = peerConnectionFactory.createAudioSource(constraints);
        lastAudioTrackId = trackId;
        audioTrack = peerConnectionFactory.createAudioTrack(trackId, audioSource);

        // Add audio data callback for file recording
        if (isRecordingToFile && audioOutputStream != null) {
            // Note: WebRTC AudioTrack doesn't have direct data callback
            // We'll implement this differently - see startRecordingToFile method
        }

        return audioTrack;
    }

    private void suspendWebRtcAudioIfActive() {
        if (audioTrack != null || audioSource != null) {
            suspendedWebRtcAudioForRecording = true;
            try {
                if (audioTrack != null) {
                    try { audioTrack.dispose(); } catch (Throwable ignored) {}
                    audioTrack = null;
                }
                if (audioSource != null) {
                    try { audioSource.dispose(); } catch (Throwable ignored) {}
                    audioSource = null;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Error suspending WebRTC audio", t);
            }
        } else {
            suspendedWebRtcAudioForRecording = false;
        }
    }

    private void resumeWebRtcAudioIfSuspended() {
        if (!suspendedWebRtcAudioForRecording) {
            return;
        }
        try {
            String trackId = (lastAudioTrackId != null) ? lastAudioTrackId : "JITSI_LOCAL_AUDIO";
            createAudioTrack(trackId);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to resume WebRTC audio after recording", t);
        } finally {
            suspendedWebRtcAudioForRecording = false;
        }
    }

    synchronized void startRecordingToFile() {
        // Redirect legacy raw PCM to AAC-in-MP4 so users get .mp4 instead of .pcm
        Log.i(TAG, "startRecordingToFile() -> startAacRecording()");
        startAacRecording();
    }

    synchronized void startRecordingToFile(boolean includeVideo) {
        // Legacy boolean variant also redirects
        Log.i(TAG, "startRecordingToFile(includeVideo=" + includeVideo + ") -> startAacRecording()");
        startAacRecording();
    }

    synchronized void stopRecordingToFile() {
        // Redirect legacy stop to AAC stop & auto-play
        Log.i(TAG, "stopRecordingToFile() -> stopAacRecordingAndPlay()");
        stopAacRecordingAndPlay();
    }

    private void cleanupFileStreams() {
        try {
            if (audioOutputStream != null) {
                audioOutputStream.close();
                audioOutputStream = null;
            }
            if (videoOutputStream != null) {
                videoOutputStream.close();
                videoOutputStream = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing file streams", e);
        }
    }

    synchronized String[] getRecordingFilePaths() {
        if (audioFile == null) {
            return new String[0];
        }

        if (videoFile != null) {
            // Audio + Video recording
            return new String[]{
                audioFile.getAbsolutePath(),
                videoFile.getAbsolutePath()
            };
        } else {
            // Audio-only recording
            return new String[]{
                audioFile.getAbsolutePath()
            };
        }
    }

    synchronized String getRecordingStatus() {
        if (!isRecordingToFile) {
            return "Not recording to file";
        }

        StringBuilder status = new StringBuilder();
        status.append("Recording: ").append(isRecordingToFile).append("\n");
        status.append("Mode: ").append(videoFile != null ? "Audio+Video" : "Audio-only").append("\n");

        if (audioFile != null) {
            status.append("Audio file: ").append(audioFile.getAbsolutePath()).append("\n");
            status.append("Audio exists: ").append(audioFile.exists()).append("\n");
            status.append("Audio size: ").append(audioFile.length()).append(" bytes\n");
        }

        if (videoFile != null) {
            status.append("Video file: ").append(videoFile.getAbsolutePath()).append("\n");
            status.append("Video exists: ").append(videoFile.exists()).append("\n");
            status.append("Video size: ").append(videoFile.length()).append(" bytes\n");
        } else {
            status.append("Video file: Not created (audio-only mode)\n");
        }

        status.append("Audio stream: ").append(audioOutputStream != null ? "open" : "null").append("\n");
        status.append("Video stream: ").append(videoOutputStream != null ? "open" : "null").append("\n");

        return status.toString();
    }

    synchronized void testFileWriting() {
        try {
            // Create a test file to verify file system access
            File testDir = new File(appContext.getFilesDir(), "JitsiRecordings");
            if (!testDir.exists()) {
                testDir.mkdirs();
            }

            File testFile = new File(testDir, "test_file.txt");
            FileOutputStream testStream = new FileOutputStream(testFile);
            String testData = "Test file writing at: " + new Date().toString() + "\n";
            testStream.write(testData.getBytes());
            testStream.flush();
            testStream.close();

            Log.i(TAG, "Test file created: " + testFile.getAbsolutePath());
            Log.i(TAG, "Test file exists: " + testFile.exists());
            Log.i(TAG, "Test file size: " + testFile.length() + " bytes");

        } catch (IOException e) {
            Log.e(TAG, "Test file writing failed", e);
        }
    }

    synchronized String listRecordingFiles() {
        try {
            File recordingsDir = new File(appContext.getFilesDir(), "JitsiRecordings");
            if (!recordingsDir.exists()) {
                return "JitsiRecordings directory does not exist";
            }

            File[] files = recordingsDir.listFiles();
            if (files == null || files.length == 0) {
                return "No files in JitsiRecordings directory";
            }

            StringBuilder fileList = new StringBuilder();
            fileList.append("Files in JitsiRecordings directory:\n");
            for (File file : files) {
                fileList.append("- ").append(file.getName())
                       .append(" (").append(file.length()).append(" bytes)")
                       .append(" - ").append(file.exists() ? "exists" : "missing")
                       .append("\n");
            }

            return fileList.toString();

        } catch (Exception e) {
            Log.e(TAG, "Error listing recording files", e);
            return "Error listing files: " + e.getMessage();
        }
    }

    synchronized void writeRecordingData(String data) {
        // This method is deprecated - audio data is now captured automatically via AudioSink
        Log.d(TAG, "writeRecordingData called (deprecated - using automatic audio capture)");
    }

    synchronized VideoTrack createVideoTrack(String trackId, Facing facing, int width, int height, int fps) {
        ensureInitialized();
        if (videoTrack != null) {
            return videoTrack;
        }

        Context context = appContext;
        EglBase egl = eglBase;
        if (context == null || egl == null) {
            throw new IllegalStateException("Not initialized");
        }

        // Force Camera1 for broader device compatibility and to avoid Camera2 stopRepeating errors
        CameraEnumerator enumeratorPrimary = new org.webrtc.Camera1Enumerator(false);
        CameraEnumerator enumeratorFallback = enumeratorPrimary; // same for Camera1, but we will try opposite facing

        // Attempt with requested facing, then opposite facing if needed
        Facing[] attempts = new Facing[] { facing, (facing == Facing.FRONT ? Facing.BACK : Facing.FRONT) };
        Exception lastError = null;

        for (Facing attemptFacing : attempts) {
            String deviceName = selectDeviceName(enumeratorPrimary, attemptFacing);
            if (deviceName == null) {
                continue;
            }
            try {
                VideoCapturer capturer = enumeratorPrimary.createCapturer(deviceName, null);
                if (capturer == null) {
                    throw new IllegalStateException("Failed to create VideoCapturer for " + deviceName);
                }

                SurfaceTextureHelper helper = SurfaceTextureHelper.create("CameraCaptureThread", egl.getEglBaseContext());
                VideoSource vSource = peerConnectionFactory.createVideoSource(false);
                capturer.initialize(helper, context, vSource.getCapturerObserver());

                boolean started = false;
                try {
                    Log.i(TAG, "Starting camera (" + attemptFacing + ") capture: " + width + "x" + height + "@" + fps);
                    capturer.startCapture(width, height, fps);
                    started = true;
                } catch (Exception e1) {
                    Log.w(TAG, "Capture failed at requested size, trying 640x480", e1);
                    try {
                        capturer.startCapture(640, 480, 15);
                        started = true;
                    } catch (Exception e2) {
                        Log.w(TAG, "Capture failed at 640x480, trying 320x240", e2);
                        try {
                            capturer.startCapture(320, 240, 10);
                            started = true;
                        } catch (Exception e3) {
                            lastError = e3;
                        }
                    }
                }

                if (started) {
                    Log.i(TAG, "Camera capture started successfully with facing=" + attemptFacing);
                    videoCapturer = capturer;
                    surfaceTextureHelper = helper;
                    videoSource = vSource;
                    videoTrack = peerConnectionFactory.createVideoTrack(trackId, vSource);
                    return videoTrack;
                } else {
                    try { capturer.dispose(); } catch (Throwable ignored) {}
                    try { helper.dispose(); } catch (Throwable ignored) {}
                    try { vSource.dispose(); } catch (Throwable ignored) {}
                }
            } catch (Exception e) {
                lastError = e;
            }
        }

        // If reached here, all attempts failed
        throw new RuntimeException("Failed to create video track with any camera: " + (lastError != null ? lastError.getMessage() : "unknown"), lastError);
    }

    synchronized void switchCamera() {
        if (videoCapturer instanceof CameraVideoCapturer) {
            ((CameraVideoCapturer) videoCapturer).switchCamera(null);
        }
    }

    synchronized void stopVideoCapture() {
        if (videoCapturer == null) return;
        try {
            videoCapturer.stopCapture();
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            // Ignore camera errors during stop - they're common in emulators
            // Log for debugging but don't throw
        }
    }

    synchronized void dispose() {
        stopVideoCapture();
        stopRecordingToFile();
        stopAudioRecordingSimulation();

        if (videoTrack != null) { videoTrack.dispose(); videoTrack = null; }
        if (videoSource != null) { videoSource.dispose(); videoSource = null; }
        if (surfaceTextureHelper != null) { surfaceTextureHelper.dispose(); surfaceTextureHelper = null; }
        if (videoCapturer != null) { videoCapturer.dispose(); videoCapturer = null; }

        if (audioTrack != null) { audioTrack.dispose(); audioTrack = null; }
        if (audioSource != null) { audioSource.dispose(); audioSource = null; }

        if (peerConnectionFactory != null) { peerConnectionFactory.dispose(); peerConnectionFactory = null; }
        if (eglBase != null) { eglBase.release(); eglBase = null; }
        appContext = null;
    }

    private void ensureInitialized() {
        if (peerConnectionFactory == null) {
            throw new IllegalStateException("LocalMediaTracks not initialized. Call initialize(context) first.");
        }
    }

    private static String selectDeviceName(CameraEnumerator enumerator, Facing facing) {
        String[] names = enumerator.getDeviceNames();
        if (names == null) return null;
        // Prefer requested facing first
        for (String name : names) {
            if (facing == Facing.FRONT && enumerator.isFrontFacing(name)) return name;
            if (facing == Facing.BACK && enumerator.isBackFacing(name)) return name;
        }
        // Fallback to any
        return names.length > 0 ? names[0] : null;
    }
}



