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
import org.webrtc.VideoSink;
import org.webrtc.VideoFrame;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.RendererCommon;

/**
 * Java utility to create and manage local microphone and camera WebRTC tracks.
 */
final class LocalMediaTracks {

    enum Facing { FRONT, BACK }

    private static LocalMediaTracks instance;
    private Facing currentCameraFacing = Facing.FRONT; // Track current camera facing mode

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

    // Video recording fields
    private android.view.Surface videoSurface;
    private boolean isGeneratingVideoFrames = false;
    private Thread videoFrameGenerationThread;

    // Separate recording fields
    private MediaRecorder audioRecorder;
    private MediaRecorder videoRecorder;
    private String audioOutputPath;
    private String videoOutputPath;
    private boolean isRecordingVideo = false;

    // WebRTC video capture
    private VideoSink recordingVideoSink;
    private SurfaceViewRenderer recordingRenderer;
    private long recordingStartTime;

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
            File out = new File(dir, "jitsi_recording_" + ts + ".mp4");
            currentOutputPath = out.getAbsolutePath();

            mediaRecorder = new MediaRecorder();
            // Suspend WebRTC mic so we can exclusively capture
            suspendWebRtcAudioIfActive();

            // Try combined audio+video first
            try {
                // Configure for both audio and video
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

                // Audio settings
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setAudioEncodingBitRate(128_000);
                mediaRecorder.setAudioSamplingRate(44100);
                mediaRecorder.setAudioChannels(1);

                // Video settings - use smaller resolution for better compatibility
                mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                mediaRecorder.setVideoEncodingBitRate(1000000); // 1 Mbps
                mediaRecorder.setVideoFrameRate(15); // Lower frame rate for stability
                mediaRecorder.setVideoSize(640, 480); // Smaller resolution

                mediaRecorder.setOutputFile(currentOutputPath);
                Log.i(TAG, "MediaRecorder output file set to: " + currentOutputPath);

                mediaRecorder.prepare();
                Log.i(TAG, "MediaRecorder prepared successfully");

                // Get the surface for video input
                videoSurface = mediaRecorder.getSurface();
                Log.i(TAG, "Video surface obtained: " + (videoSurface != null ? "SUCCESS" : "FAILED"));

                mediaRecorder.start();
                Log.i(TAG, "Combined audio+video recording started: " + currentOutputPath);

                // Start generating video frames to the surface
                startVideoFrameGeneration();

            } catch (Throwable videoError) {
                Log.w(TAG, "Combined recording failed, falling back to audio-only", videoError);
                // Fall back to audio-only recording with same filename
                safeReleaseMediaRecorder();
                startAudioOnlyRecordingWithPath(currentOutputPath);
            }

        } catch (Throwable t) {
            Log.e(TAG, "startAacRecording completely failed", t);
            safeReleaseMediaRecorder();
            resumeWebRtcAudioIfSuspended();
            throw new RuntimeException(t);
        }
    }

    synchronized void startAudioOnlyRecording() {
        if (mediaRecorder != null) {
            Log.w(TAG, "MediaRecorder already active");
            return;
        }
        try {
            File dir = getMusicDir();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File out = new File(dir, "jitsi_audio_" + ts + ".mp4");
            currentOutputPath = out.getAbsolutePath();

            startAudioOnlyRecordingWithPath(currentOutputPath);
        } catch (Throwable t) {
            Log.e(TAG, "startAudioOnlyRecording failed", t);
            safeReleaseMediaRecorder();
            resumeWebRtcAudioIfSuspended();
            throw new RuntimeException(t);
        }
    }

    synchronized void startAudioOnlyRecordingWithPath(String outputPath) {
        if (mediaRecorder != null) {
            Log.w(TAG, "MediaRecorder already active");
            return;
        }
        try {
            currentOutputPath = outputPath;
            Log.i(TAG, "Starting audio-only recording to: " + currentOutputPath);

            File outputFile = new File(currentOutputPath);
            File dir = outputFile.getParentFile();
            Log.i(TAG, "Directory exists: " + dir.exists() + ", writable: " + dir.canWrite());

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

            Log.i(TAG, "MediaRecorder configured, preparing...");
            mediaRecorder.prepare();
            Log.i(TAG, "MediaRecorder prepared, starting...");
            mediaRecorder.start();
            Log.i(TAG, "Audio-only recording started successfully: " + currentOutputPath);
        } catch (Throwable t) {
            Log.e(TAG, "startAudioOnlyRecordingWithPath failed", t);
            safeReleaseMediaRecorder();
            resumeWebRtcAudioIfSuspended();
            throw new RuntimeException(t);
        }
    }

    synchronized void stopAacRecordingAndPlay() {
        Log.i(TAG, "Stopping recording...");
        try {
            // Stop video frame generation first
            stopVideoFrameGeneration();

            if (mediaRecorder != null) {
                Log.i(TAG, "Stopping MediaRecorder...");
                try {
                    mediaRecorder.stop();
                    Log.i(TAG, "MediaRecorder stopped successfully");
                } catch (Throwable t) {
                    Log.e(TAG, "Error stopping MediaRecorder", t);
                }
            } else {
                Log.w(TAG, "MediaRecorder is null, nothing to stop");
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

        // Check if file was actually created
        File outputFile = new File(currentOutputPath);
        Log.i(TAG, "Recording file exists: " + outputFile.exists());
        Log.i(TAG, "Recording file size: " + outputFile.length() + " bytes");
        Log.i(TAG, "Recording file path: " + currentOutputPath);

        // Make file visible in media DB and also copy to public Music for easier user access
        try {
            MediaScannerConnection.scanFile(
                appContext,
                new String[]{ currentOutputPath },
                null,
                null);
            Log.i(TAG, "Media scan completed");
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

        // Determine MIME type based on filename - combined files are video files with audio
        boolean isVideoFile = fileName.contains("jitsi_video_") || fileName.contains("jitsi_combined_");
        String mimeType = isVideoFile ? "video/mp4" : "audio/mp4";
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);

        // Force the correct file extension by setting it explicitly
        if (fileName.endsWith(".mp4")) {
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        }

        // Set media type for proper categorization
        values.put(MediaStore.Files.FileColumns.MEDIA_TYPE,
            isVideoFile ? MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO : MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use Download directory for MediaStore.Files since Music/Movies are not allowed
            String relativePath = Environment.DIRECTORY_DOWNLOADS + "/JitsiRecordings";
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        }

        // Use MediaStore.Files for both audio and video to avoid automatic extension changes
        // This prevents Android from automatically changing .mp4 to .m4a for audio files
        Uri collection = MediaStore.Files.getContentUri("external");
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
        videoSurface = null;
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

    private void startVideoFrameGeneration() {
        if (videoSurface == null) {
            Log.w(TAG, "Video surface not available");
            return;
        }

        isGeneratingVideoFrames = true;
        Log.i(TAG, "Starting video frame generation");

        videoFrameGenerationThread = new Thread(() -> {
            int frameCount = 0;
            while (isGeneratingVideoFrames && videoSurface != null) {
                try {
                    android.graphics.Canvas canvas = videoSurface.lockCanvas(null);
                    if (canvas != null) {
                        // Always draw something to ensure video track is active
                        android.graphics.Paint paint = new android.graphics.Paint();

                        // Check if camera is enabled by looking at videoTrack
                        if (videoTrack != null) {
                            // Camera is enabled - draw blue background
                            paint.setColor(android.graphics.Color.BLUE);
                            canvas.drawRect(0, 0, 640, 480, paint);

                            paint.setColor(android.graphics.Color.WHITE);
                            paint.setTextSize(32);
                            canvas.drawText("CAMERA ENABLED", 20, 50, paint);
                            canvas.drawText("Frame: " + frameCount, 20, 100, paint);
                        } else {
                            // Camera is disabled - draw black screen
                            canvas.drawColor(android.graphics.Color.BLACK);

                            paint.setColor(android.graphics.Color.WHITE);
                            paint.setTextSize(32);
                            canvas.drawText("CAMERA DISABLED", 20, 50, paint);
                            canvas.drawText("Frame: " + frameCount, 20, 100, paint);
                        }

                        videoSurface.unlockCanvasAndPost(canvas);
                        frameCount++;

                        // Log every 30 frames (1 second) to verify it's working
                        if (frameCount % 30 == 0) {
                            Log.i(TAG, "Generated " + frameCount + " video frames");
                        }
                    }
                    Thread.sleep(33); // ~30 FPS
                } catch (Exception e) {
                    Log.w(TAG, "Error generating video frame", e);
                    break;
                }
            }
            Log.i(TAG, "Video frame generation stopped. Total frames: " + frameCount);
        });

        videoFrameGenerationThread.start();
    }

    private void stopVideoFrameGeneration() {
        isGeneratingVideoFrames = false;
        if (videoFrameGenerationThread != null) {
            try {
                videoFrameGenerationThread.interrupt();
                videoFrameGenerationThread.join(1000); // Wait up to 1 second
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while stopping video frame generation");
            }
            videoFrameGenerationThread = null;
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
        // Don't suspend WebRTC audio - let both WebRTC and MediaRecorder capture audio
        // This allows the camera to remain active during recording
        Log.i(TAG, "Keeping WebRTC audio active during recording - no suspension needed");
        suspendedWebRtcAudioForRecording = false;
    }

    private void resumeWebRtcAudioIfSuspended() {
        // No need to resume since we never suspended WebRTC audio
        Log.i(TAG, "WebRTC audio was never suspended - no resume needed");
    }

    synchronized void startRecordingToFile() {
        // Use combined recording for single file output
        Log.i(TAG, "startRecordingToFile() -> startCombinedRecording()");
        startCombinedRecording();
    }

    synchronized void startRecordingToFile(boolean includeVideo) {
        // Use combined recording for single file output
        Log.i(TAG, "startRecordingToFile(includeVideo=" + includeVideo + ") -> startCombinedRecording()");
        startCombinedRecording();
    }

    synchronized void startCombinedRecording() {
        Log.i(TAG, "Starting synchronized combined audio+video recording in single file");

        if (mediaRecorder != null) {
            Log.w(TAG, "MediaRecorder already active");
            return;
        }

        try {
            File dir = getMusicDir();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File out = new File(dir, "jitsi_combined_" + ts + ".mp4");
            currentOutputPath = out.getAbsolutePath();

            Log.i(TAG, "Starting synchronized combined recording to: " + currentOutputPath);

            mediaRecorder = new MediaRecorder();

            // Configure for synchronized audio and video in single file
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);

            // Use SURFACE source for reliable combined recording
            // This avoids camera conflicts with WebRTC and ensures stable recording
            try {
                Log.i(TAG, "Setting SURFACE source for reliable combined recording");
                mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                Log.i(TAG, "Successfully set SURFACE source for combined recording");
            } catch (Exception e) {
                Log.e(TAG, "SURFACE source failed", e);
                throw new RuntimeException("Failed to set SURFACE video source", e);
            }

            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

            // Audio settings - optimized for sync
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(128_000);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setAudioChannels(1);

            // Video settings - optimized for sync and compatibility
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoEncodingBitRate(2000000); // 2 Mbps for better quality
            mediaRecorder.setVideoFrameRate(30); // 30 FPS for better sync
            mediaRecorder.setVideoSize(640, 480); // Standard resolution

            // Set maximum file size and duration to prevent issues
            mediaRecorder.setMaxFileSize(100 * 1024 * 1024); // 100MB max
            mediaRecorder.setMaxDuration(30 * 60 * 1000); // 30 minutes max

            mediaRecorder.setOutputFile(currentOutputPath);

            Log.i(TAG, "MediaRecorder configured for synchronized combined recording");
            mediaRecorder.prepare();
            Log.i(TAG, "MediaRecorder prepared successfully");

            // Get the surface for video input (needed even with CAMERA source)
            videoSurface = mediaRecorder.getSurface();
            Log.i(TAG, "Video surface obtained: " + (videoSurface != null ? "SUCCESS" : "FAILED"));

            mediaRecorder.start();
            Log.i(TAG, "Synchronized combined audio+video recording started: " + currentOutputPath);
            Log.i(TAG, "MediaRecorder is now active: " + (mediaRecorder != null));
            Log.i(TAG, "Video surface is available: " + (videoSurface != null));

            // Start synchronized video frame generation
            Log.i(TAG, "About to start synchronized video frame generation");
            startSynchronizedVideoFrameGeneration();
            Log.i(TAG, "Synchronized video frame generation started");

        } catch (Throwable t) {
            Log.e(TAG, "Failed to start synchronized combined recording", t);
            safeReleaseMediaRecorder();

            // Clear the combined recording path since it failed
            currentOutputPath = null;

            // For now, let's not fallback to separate recording to test combined recording
            // This will help us identify if the combined recording can work
            Log.e(TAG, "Combined recording failed - not falling back to separate recording for testing");
            throw new RuntimeException("Combined recording failed: " + t.getMessage(), t);

            // TODO: Re-enable fallback after testing combined recording
            /*
            // Fallback to separate recording if combined recording fails
            Log.i(TAG, "Falling back to separate recording due to combined recording failure");
            try {
                startSeparateRecording();
                Log.i(TAG, "Successfully started separate recording as fallback");
            } catch (Throwable fallbackError) {
                Log.e(TAG, "Fallback to separate recording also failed", fallbackError);
                throw new RuntimeException("Both combined and separate recording failed", fallbackError);
            }
            */
        }
    }

    private void startSynchronizedVideoFrameGeneration() {
        Log.i(TAG, "startSynchronizedVideoFrameGeneration called");
        if (videoSurface == null) {
            Log.w(TAG, "Video surface not available for synchronized recording");
            return;
        }

        isGeneratingVideoFrames = true;
        Log.i(TAG, "Starting synchronized video frame generation with surface: " + videoSurface);

        // Try to capture real camera frames first
        if (setupRealCameraCapture()) {
            Log.i(TAG, "Successfully set up real camera capture");
            return;
        }

        // Fallback to synthetic frames if real camera capture fails
        Log.i(TAG, "Real camera capture failed, falling back to synthetic frames");
        startSyntheticFrameGeneration();
    }

    private boolean setupRealCameraCapture() {
        try {
            Log.i(TAG, "=== SETTING UP REAL CAMERA CAPTURE ===");

            // Try to get the main WebRTC video track
            VideoTrack mainVideoTrack = getMainWebRtcVideoTrack();

            if (mainVideoTrack != null) {
                Log.i(TAG, "Found main WebRTC video track - setting up real camera capture");
                Log.i(TAG, "Video track ID: " + mainVideoTrack.id());
                Log.i(TAG, "Video track enabled: " + mainVideoTrack.enabled());
                Log.i(TAG, "Video track state: " + mainVideoTrack.state());

                // Create a VideoSink to capture frames from the main WebRTC video track
                recordingVideoSink = new VideoSink() {
                    @Override
                    public void onFrame(VideoFrame frame) {
                        Log.d(TAG, "Received real camera frame: " + frame.getBuffer().getWidth() + "x" + frame.getBuffer().getHeight());
                        if (videoSurface != null && isGeneratingVideoFrames) {
                            renderRealCameraFrameToSurface(frame);
                        } else {
                            Log.w(TAG, "Cannot render real camera frame - videoSurface: " + (videoSurface != null) + ", isGeneratingVideoFrames: " + isGeneratingVideoFrames);
                        }
                    }
                };

                // Add the VideoSink to the main WebRTC video track
                mainVideoTrack.addSink(recordingVideoSink);
                Log.i(TAG, "Real camera capture setup complete - will capture from active camera");
                return true;
            } else {
                Log.i(TAG, "No main WebRTC video track found - will use synthetic frames");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup real camera capture", e);
            return false;
        }
    }

    private void renderRealCameraFrameToSurface(VideoFrame frame) {
        try {
            Log.d(TAG, "Rendering real camera frame to surface");

            // Convert WebRTC VideoFrame to Android Bitmap
            android.graphics.Bitmap bitmap = videoFrameToBitmap(frame);

            if (bitmap != null) {
                android.graphics.Canvas canvas = videoSurface.lockCanvas(null);
                if (canvas != null) {
                    // Clear the canvas first
                    canvas.drawColor(android.graphics.Color.BLACK);

                    // Draw the real camera frame
                    canvas.drawBitmap(bitmap, 0, 0, null);

                    // Add recording overlay
                    android.graphics.Paint paint = new android.graphics.Paint();
                    paint.setColor(android.graphics.Color.WHITE);
                    paint.setTextSize(24);
                    paint.setStyle(android.graphics.Paint.Style.FILL);
                    paint.setShadowLayer(2, 2, 2, android.graphics.Color.BLACK);

                    // Add recording indicator
                    canvas.drawText("RECORDING", 20, 40, paint);

                    // Add timestamp
                    long currentTime = System.currentTimeMillis();
                    canvas.drawText("Time: " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date(currentTime)), 20, 70, paint);

                    videoSurface.unlockCanvasAndPost(canvas);
                    Log.d(TAG, "Successfully rendered real camera frame");
                } else {
                    Log.w(TAG, "Failed to lock canvas for real camera frame");
                }
                bitmap.recycle();
            } else {
                Log.w(TAG, "Failed to convert video frame to bitmap");
            }
        } catch (Exception e) {
            Log.w(TAG, "Error rendering real camera frame to surface", e);
        }
    }

    private android.graphics.Bitmap videoFrameToBitmap(VideoFrame frame) {
        try {
            // Get the video frame buffer
            VideoFrame.I420Buffer i420Buffer = frame.getBuffer().toI420();

            // Create bitmap with the frame dimensions
            int width = i420Buffer.getWidth();
            int height = i420Buffer.getHeight();
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);

            // Convert I420 to ARGB with full color information
            int[] argb = new int[width * height];

            // Get Y, U, V plane data
            java.nio.ByteBuffer yPlane = i420Buffer.getDataY();
            java.nio.ByteBuffer uPlane = i420Buffer.getDataU();
            java.nio.ByteBuffer vPlane = i420Buffer.getDataV();

            byte[] yData = new byte[yPlane.remaining()];
            byte[] uData = new byte[uPlane.remaining()];
            byte[] vData = new byte[vPlane.remaining()];

            yPlane.get(yData);
            uPlane.get(uData);
            vPlane.get(vData);

            // Proper I420 to ARGB conversion with full color
            int uvWidth = width / 2;
            int uvHeight = height / 2;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int yIndex = y * width + x;
                    int uvIndex = (y / 2) * uvWidth + (x / 2);

                    if (yIndex < yData.length && uvIndex < uData.length && uvIndex < vData.length) {
                        int yValue = yData[yIndex] & 0xFF;
                        int uValue = uData[uvIndex] & 0xFF;
                        int vValue = vData[uvIndex] & 0xFF;

                        // YUV to RGB conversion
                        int r = (int) (yValue + 1.402 * (vValue - 128));
                        int g = (int) (yValue - 0.344136 * (uValue - 128) - 0.714136 * (vValue - 128));
                        int b = (int) (yValue + 1.772 * (uValue - 128));

                        // Clamp values to 0-255 range
                        r = Math.max(0, Math.min(255, r));
                        g = Math.max(0, Math.min(255, g));
                        b = Math.max(0, Math.min(255, b));

                        // Create ARGB pixel
                        argb[yIndex] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
            }

            bitmap.setPixels(argb, 0, width, 0, 0, width, height);
            i420Buffer.release();

            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error converting video frame to bitmap", e);
            return null;
        }
    }

    private void startSyntheticFrameGeneration() {
        videoFrameGenerationThread = new Thread(() -> {
            int frameCount = 0;
            long startTime = System.currentTimeMillis();
            long lastFrameTime = startTime;

            Log.i(TAG, "Synthetic video frame generation thread started");

            while (isGeneratingVideoFrames && videoSurface != null) {
                try {
                    long currentTime = System.currentTimeMillis();
                    long frameInterval = 1000 / 30; // 30 FPS = 33.33ms per frame
                    long timeSinceLastFrame = currentTime - lastFrameTime;

                    // Only generate frame if enough time has passed (maintain 30 FPS)
                    if (timeSinceLastFrame >= frameInterval) {
                        android.graphics.Canvas canvas = videoSurface.lockCanvas(null);
                        if (canvas != null) {
                            android.graphics.Paint paint = new android.graphics.Paint();

                            // Create a more visually appealing and playable video content
                            // Use a gradient background for better video quality
                            android.graphics.LinearGradient gradient = new android.graphics.LinearGradient(
                                0, 0, 640, 480,
                                new int[]{android.graphics.Color.BLUE, android.graphics.Color.CYAN, android.graphics.Color.GREEN},
                                new float[]{0f, 0.5f, 1f},
                                android.graphics.Shader.TileMode.CLAMP
                            );
                            paint.setShader(gradient);
                            canvas.drawRect(0, 0, 640, 480, paint);
                            paint.setShader(null); // Clear shader

                            // Add text overlay with better visibility
                            paint.setColor(android.graphics.Color.WHITE);
                            paint.setTextSize(28);
                            paint.setStyle(android.graphics.Paint.Style.FILL);
                            paint.setShadowLayer(2, 2, 2, android.graphics.Color.BLACK);

                            // Check if camera is enabled by looking at videoTrack
                            if (videoTrack != null && videoTrack.enabled()) {
                                canvas.drawText("CAMERA ACTIVE", 20, 50, paint);
                            } else {
                                canvas.drawText("CAMERA DISABLED", 20, 50, paint);
                            }

                            canvas.drawText("Frame: " + frameCount, 20, 90, paint);

                            // Add recording duration
                            long duration = (currentTime - startTime) / 1000;
                            canvas.drawText("Duration: " + duration + "s", 20, 130, paint);

                            // Add sync indicator
                            canvas.drawText("SYNC: " + timeSinceLastFrame + "ms", 20, 170, paint);

                            // Add a moving element to make the video more interesting
                            int circleX = (int) ((frameCount * 5) % 600) + 20;
                            int circleY = 200;
                            paint.setColor(android.graphics.Color.RED);
                            paint.setStyle(android.graphics.Paint.Style.FILL);
                            canvas.drawCircle(circleX, circleY, 15, paint);

                            // Add timestamp
                            paint.setColor(android.graphics.Color.YELLOW);
                            paint.setTextSize(20);
                            canvas.drawText("Recording: " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date(currentTime)), 20, 250, paint);

                            // Add a small white dot to ensure frame is not completely empty
                            paint.setColor(android.graphics.Color.WHITE);
                            canvas.drawCircle(10, 10, 2, paint);

                            videoSurface.unlockCanvasAndPost(canvas);
                            frameCount++;
                            lastFrameTime = currentTime;

                            // Log every 30 frames (1 second at 30 FPS)
                            if (frameCount % 30 == 0) {
                                long logDuration = (currentTime - startTime) / 1000;
                                Log.i(TAG, "Synthetic recording: " + frameCount + " frames, " + logDuration + "s, avg interval: " + (timeSinceLastFrame) + "ms");
                            }
                        }
                    }

                    // Sleep for a short time to prevent busy waiting
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Log.i(TAG, "Synthetic video frame generation interrupted");
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "Error generating synthetic video frame", e);
                    break;
                }
            }
            Log.i(TAG, "Synthetic video frame generation stopped. Total frames: " + frameCount);
        });

        videoFrameGenerationThread.start();
    }

    synchronized void startSeparateRecording() {
        Log.i(TAG, "Starting separate audio and video recording");

        try {
            // Start audio recording
            startAudioRecording();

            // Start video recording
            startVideoRecording();

            Log.i(TAG, "Separate recording started successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start separate recording", t);
            stopSeparateRecording();
            throw new RuntimeException(t);
        }
    }

    synchronized void startAudioRecording() {
        if (audioRecorder != null) {
            Log.w(TAG, "Audio recorder already active");
            return;
        }

        try {
            File dir = getMusicDir();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File out = new File(dir, "jitsi_audio_" + ts + ".mp4");
            audioOutputPath = out.getAbsolutePath();

            Log.i(TAG, "Starting audio recording to: " + audioOutputPath);

            audioRecorder = new MediaRecorder();
            suspendWebRtcAudioIfActive();

            audioRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            audioRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            audioRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            audioRecorder.setAudioEncodingBitRate(128_000);
            audioRecorder.setAudioSamplingRate(44100);
            audioRecorder.setAudioChannels(1);
            audioRecorder.setOutputFile(audioOutputPath);

            audioRecorder.prepare();
            audioRecorder.start();

            isRecordingAudio = true;
            Log.i(TAG, "Audio recording started successfully: " + audioOutputPath);

        } catch (Throwable t) {
            Log.e(TAG, "Failed to start audio recording", t);
            safeReleaseAudioRecorder();
            resumeWebRtcAudioIfSuspended();
            throw new RuntimeException(t);
        }
    }

    synchronized void startVideoRecording() {
        if (videoRecorder != null) {
            Log.w(TAG, "Video recorder already active");
            return;
        }

        try {
            File dir = getMusicDir();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File out = new File(dir, "jitsi_video_" + ts + ".mp4");
            videoOutputPath = out.getAbsolutePath();

            Log.i(TAG, "Starting simple black frame video recording to: " + videoOutputPath);

            videoRecorder = new MediaRecorder();

            // Use SURFACE source for simple black frame generation
            videoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            videoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            videoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            videoRecorder.setVideoEncodingBitRate(1000000); // 1 Mbps for better quality
            videoRecorder.setVideoFrameRate(15); // 15 FPS for better compatibility
            videoRecorder.setVideoSize(640, 480); // Standard resolution
            videoRecorder.setOutputFile(videoOutputPath);

            // Use standard H264 settings for better compatibility
            Log.i(TAG, "Using standard H264 settings for video recording");

            videoRecorder.prepare();

            // Get the surface for video input
            videoSurface = videoRecorder.getSurface();
            Log.i(TAG, "Video surface obtained: " + (videoSurface != null ? "SUCCESS" : "FAILED"));

            videoRecorder.start();

            isRecordingVideo = true;
            recordingStartTime = System.currentTimeMillis();
            Log.i(TAG, "Simple video recording started successfully: " + videoOutputPath);

            // Start synchronized video frame generation for separate recording
            startSynchronizedVideoFrameGeneration();

        } catch (Throwable t) {
            Log.e(TAG, "Failed to start video recording", t);
            safeReleaseVideoRecorder();
            throw new RuntimeException(t);
        }
    }

    private void setupWebRtcVideoCapture() {
        // Try to get video track from main WebRTC session first
        VideoTrack mainVideoTrack = getMainWebRtcVideoTrack();

        if (mainVideoTrack != null) {
            Log.i(TAG, "Found main WebRTC video track - setting up video capture");
            Log.i(TAG, "Video track ID: " + mainVideoTrack.id());
            Log.i(TAG, "Video track enabled: " + mainVideoTrack.enabled());

            try {
                // Create a VideoSink to capture frames from main WebRTC video track
                recordingVideoSink = new VideoSink() {
                    @Override
                    public void onFrame(VideoFrame frame) {
                        if (videoSurface != null && isRecordingVideo) {
                            renderRealCameraFrameToSurface(frame);
                        }
                    }
                };

                // Add the VideoSink to the main WebRTC video track
                mainVideoTrack.addSink(recordingVideoSink);
                Log.i(TAG, "Main WebRTC video capture setup complete - will capture from active camera");
                return;

            } catch (Throwable t) {
                Log.e(TAG, "Failed to setup main WebRTC video capture", t);
            }
        }

        // Fallback to our own video track
        if (videoTrack == null) {
            Log.i(TAG, "No active WebRTC video track found - recording black frames");
            Log.i(TAG, "This is normal if camera is disabled or not yet started");
            startSynchronizedVideoFrameGeneration();
            return;
        }

        try {
            Log.i(TAG, "Using local video track - setting up video capture");
            Log.i(TAG, "Video track ID: " + videoTrack.id());
            Log.i(TAG, "Video track enabled: " + videoTrack.enabled());

            // Create a VideoSink to capture frames from our video track
            recordingVideoSink = new VideoSink() {
                @Override
                public void onFrame(VideoFrame frame) {
                    if (videoSurface != null && isRecordingVideo) {
                        renderRealCameraFrameToSurface(frame);
                    }
                }
            };

            // Add the VideoSink to our video track
            videoTrack.addSink(recordingVideoSink);
            Log.i(TAG, "Local video capture setup complete - will capture from active camera");

        } catch (Throwable t) {
            Log.e(TAG, "Failed to setup video capture, falling back to black frames", t);
            startSynchronizedVideoFrameGeneration();
        }
    }

    private VideoTrack getMainWebRtcVideoTrack() {
        try {
            Log.i(TAG, "=== SEARCHING FOR MAIN WEBRTC VIDEO TRACK ===");
            Log.i(TAG, "videoTrack: " + (videoTrack != null ? "EXISTS" : "NULL"));

            if (videoTrack != null) {
                Log.i(TAG, "videoTrack.id(): " + videoTrack.id());
                Log.i(TAG, "videoTrack.enabled(): " + videoTrack.enabled());
                Log.i(TAG, "videoTrack.state(): " + videoTrack.state());
            }

            // First, try to use our own video track if it exists and is active
            if (videoTrack != null && videoTrack.enabled()) {
                Log.i(TAG, "Using our own active video track: " + videoTrack.id());
                return videoTrack;
            }

            // Try to create a new video track that can share the camera
            try {
                Log.i(TAG, "Attempting to create shared camera video track...");
                String recordingTrackId = "JITSI_RECORDING_SHARED_" + System.currentTimeMillis();

                // Create a video track with the same camera settings as the main call
                VideoTrack sharedTrack = createSharedCameraVideoTrack(recordingTrackId);

                if (sharedTrack != null) {
                    Log.i(TAG, "Successfully created shared camera video track: " + sharedTrack.id());
                    return sharedTrack;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to create shared camera video track", e);
            }

            Log.i(TAG, "No active video track found - will use synthetic frames");
            return null;

        } catch (Exception e) {
            Log.w(TAG, "Failed to get main WebRTC video track", e);
            return null;
        }
    }

    private VideoTrack createSharedCameraVideoTrack(String trackId) {
        try {
            Log.i(TAG, "Creating shared camera video track: " + trackId);

            // Create a new video track that can capture from the camera
            // This will be used for recording while the main WebRTC session continues

            ensureInitialized();

            // Use the current camera facing mode based on user switching
            Facing currentFacing = currentCameraFacing;
            Log.i(TAG, "Using current camera facing mode for recording: " + currentFacing);

            // Create a video track with the current camera facing mode
            VideoTrack recordingVideoTrack = createVideoTrack(trackId, currentFacing, 640, 480, 30);

            if (recordingVideoTrack != null) {
                Log.i(TAG, "Successfully created shared camera video track: " + recordingVideoTrack.id() + " with facing: " + currentFacing);
                return recordingVideoTrack;
            } else {
                Log.w(TAG, "Failed to create video track for recording");
                return null;
            }

        } catch (Exception e) {
            Log.w(TAG, "Failed to create shared camera video track", e);
            return null;
        }
    }


    private void startSimpleBlackFrameGeneration() {
        Log.i(TAG, "Starting simple black frame generation");
        isGeneratingVideoFrames = true;

        videoFrameGenerationThread = new Thread(() -> {
            int frameCount = 0;
            long lastFrameTime = System.currentTimeMillis();

            while (isGeneratingVideoFrames && videoSurface != null && isRecordingVideo) {
                try {
                    android.graphics.Canvas canvas = videoSurface.lockCanvas(null);
                    if (canvas != null) {
                        // Draw black background
                        canvas.drawColor(android.graphics.Color.BLACK);

                        // Add a small white dot to ensure the frame is not completely empty
                        // This helps with video player compatibility
                        android.graphics.Paint paint = new android.graphics.Paint();
                        paint.setColor(android.graphics.Color.WHITE);
                        canvas.drawCircle(10, 10, 2, paint);

                        videoSurface.unlockCanvasAndPost(canvas);
                        frameCount++;

                        // Log every 15 frames (1 second at 15 FPS)
                        if (frameCount % 15 == 0) {
                            long duration = (System.currentTimeMillis() - recordingStartTime) / 1000;
                            long currentTime = System.currentTimeMillis();
                            long frameInterval = currentTime - lastFrameTime;
                            Log.i(TAG, "Generated " + frameCount + " black frames, duration: " + duration + "s, last frame interval: " + frameInterval + "ms");
                            lastFrameTime = currentTime;
                        }
                    } else {
                        Log.w(TAG, "Failed to lock canvas for frame " + frameCount);
                    }

                    // 15 FPS - 66ms between frames
                    Thread.sleep(66);
                } catch (Exception e) {
                    Log.w(TAG, "Error generating black frame " + frameCount, e);
                    // Don't break immediately, try to continue
                    try {
                        Thread.sleep(100); // Wait a bit before retrying
                    } catch (InterruptedException ie) {
                        Log.i(TAG, "Frame generation thread interrupted");
                        break;
                    }
                }
            }
            Log.i(TAG, "Simple black frame generation stopped. Total frames: " + frameCount + ", duration: " + ((System.currentTimeMillis() - recordingStartTime) / 1000) + "s");
        });

        videoFrameGenerationThread.start();
    }

    synchronized void stopRecordingToFile() {
        // Use combined recording stop
        Log.i(TAG, "stopRecordingToFile() -> stopCombinedRecording()");
        stopCombinedRecording();
    }

    synchronized void stopCombinedRecording() {
        Log.i(TAG, "Stopping combined recording...");
        Log.i(TAG, "Current output path before stop: " + currentOutputPath);
        Log.i(TAG, "MediaRecorder state: " + (mediaRecorder != null ? "active" : "null"));

        try {
            // Stop video frame generation first
            stopVideoFrameGeneration();

            if (mediaRecorder != null) {
                Log.i(TAG, "Stopping combined MediaRecorder...");
                try {
                    mediaRecorder.stop();
                    Log.i(TAG, "Combined MediaRecorder stopped successfully");
                } catch (Throwable t) {
                    Log.e(TAG, "Error stopping combined MediaRecorder", t);
                }
            } else {
                Log.w(TAG, "Combined MediaRecorder is null, nothing to stop");
            }
        } finally {
            safeReleaseMediaRecorder();
        }

        Log.i(TAG, "Current output path after stop: " + currentOutputPath);

        if (currentOutputPath == null) {
            Log.w(TAG, "No output path to save - this indicates combined recording was not properly started");
            Log.w(TAG, "Checking if we have separate recording paths instead...");

            // Check for separate recording paths as fallback
            if (audioOutputPath != null || videoOutputPath != null) {
                Log.i(TAG, "Found separate recording paths - audio: " + audioOutputPath + ", video: " + videoOutputPath);
                saveRecordingFilesToPublic();
                return;
            }

            Log.w(TAG, "No recording paths found at all");
            return;
        }

        // Check if file was actually created
        File outputFile = new File(currentOutputPath);
        Log.i(TAG, "Combined recording file exists: " + outputFile.exists());
        Log.i(TAG, "Combined recording file size: " + outputFile.length() + " bytes");
        Log.i(TAG, "Combined recording file path: " + currentOutputPath);

        // Save file to public directory and scan it
        saveAndScanFile(currentOutputPath);

        Log.i(TAG, "Combined recording stopped and saved");
    }

    synchronized void stopSeparateRecording() {
        Log.i(TAG, "Stopping separate recording...");

        // Stop video recording first
        stopVideoRecording();

        // Stop audio recording
        stopAudioRecording();

        // Save files to public directory and scan them
        saveRecordingFilesToPublic();

        Log.i(TAG, "Separate recording stopped");
    }

    private void saveRecordingFilesToPublic() {
        // Save audio file
        if (audioOutputPath != null) {
            saveAndScanFile(audioOutputPath);
        }

        // Save video file
        if (videoOutputPath != null) {
            saveAndScanFile(videoOutputPath);
        }
    }

    private void saveAndScanFile(String filePath) {
        if (filePath == null) {
            Log.w(TAG, "File path is null, cannot save file");
            return;
        }

        File outputFile = new File(filePath);
        Log.i(TAG, "Checking file: " + filePath);
        Log.i(TAG, "File exists: " + outputFile.exists());
        Log.i(TAG, "File size: " + outputFile.length() + " bytes");
        Log.i(TAG, "File readable: " + outputFile.canRead());
        Log.i(TAG, "File writable: " + outputFile.canWrite());

        if (!outputFile.exists()) {
            Log.w(TAG, "File does not exist: " + filePath);
            return;
        }

        if (outputFile.length() == 0) {
            Log.w(TAG, "File is empty (0 bytes): " + filePath);
            return;
        }

        if (outputFile.length() < 1024) {
            Log.w(TAG, "File is very small (" + outputFile.length() + " bytes), may not be a valid video: " + filePath);
        }

        // Scan file to make it visible in media database
        try {
            MediaScannerConnection.scanFile(
                appContext,
                new String[]{ filePath },
                null,
                null);
            Log.i(TAG, "Media scan completed for: " + filePath);
        } catch (Throwable t) {
            Log.w(TAG, "Media scan failed for: " + filePath, t);
        }

        // Copy to public directory for easier access
        try {
            Uri publicUri = saveToPublicMusic(filePath);
            if (publicUri != null) {
                Log.i(TAG, "Successfully copied recording to public directory: " + publicUri);
                Log.i(TAG, "File should now be visible in file manager with correct extension");
            } else {
                Log.w(TAG, "Failed to copy to public directory: " + filePath);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Copy to public directory failed for: " + filePath, t);
        }
    }

    synchronized void stopAudioRecording() {
        if (audioRecorder != null) {
            Log.i(TAG, "Stopping audio recording...");
            try {
                audioRecorder.stop();
                Log.i(TAG, "Audio recording stopped successfully");
            } catch (Throwable t) {
                Log.e(TAG, "Error stopping audio recording", t);
            }
        }
        safeReleaseAudioRecorder();
        resumeWebRtcAudioIfSuspended();
    }

    synchronized void stopVideoRecording() {
        if (videoRecorder != null) {
            Log.i(TAG, "Stopping simple video recording...");
            try {
                // Stop the MediaRecorder first
                videoRecorder.stop();
                Log.i(TAG, "Video recording stopped successfully");

                // Then stop frame generation after MediaRecorder is stopped
                stopVideoFrameGeneration();
            } catch (Throwable t) {
                Log.e(TAG, "Error stopping video recording", t);
                // Make sure to stop frame generation even if MediaRecorder fails
                stopVideoFrameGeneration();
            }
        }
        safeReleaseVideoRecorder();
    }

    private void stopWebRtcVideoCapture() {
        if (recordingVideoSink != null && videoTrack != null) {
            try {
                videoTrack.removeSink(recordingVideoSink);
                Log.i(TAG, "WebRTC video sink removed");
            } catch (Throwable t) {
                Log.w(TAG, "Error removing WebRTC video sink", t);
            }
            recordingVideoSink = null;
        }
    }

    private void safeReleaseAudioRecorder() {
        if (audioRecorder != null) {
            try { audioRecorder.reset(); } catch (Throwable ignored) {}
            try { audioRecorder.release(); } catch (Throwable ignored) {}
            audioRecorder = null;
        }
        isRecordingAudio = false;
    }

    private void safeReleaseVideoRecorder() {
        if (videoRecorder != null) {
            try { videoRecorder.reset(); } catch (Throwable ignored) {}
            try { videoRecorder.release(); } catch (Throwable ignored) {}
            videoRecorder = null;
        }
        videoSurface = null;
        isRecordingVideo = false;
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
        java.util.List<String> paths = new java.util.ArrayList<>();

        // Check for combined recording first
        if (currentOutputPath != null) {
            paths.add(currentOutputPath);
        }

        // Check for separate recording files
        if (audioOutputPath != null) {
            paths.add(audioOutputPath);
        }

        if (videoOutputPath != null) {
            paths.add(videoOutputPath);
        }

        return paths.toArray(new String[0]);
    }

    synchronized String getRecordingStatus() {
        // Check if any recording is active
        if (!isRecordingAudio && !isRecordingVideo && mediaRecorder == null) {
            return "Not recording to file";
        }

        StringBuilder status = new StringBuilder();

        // Check if we're using combined recording
        if (mediaRecorder != null && currentOutputPath != null) {
            status.append("Recording Mode: Combined Audio+Video\n");
            status.append("Combined Recording: ").append(mediaRecorder != null ? "active" : "stopped").append("\n");

            File outputFile = new File(currentOutputPath);
            status.append("Combined file: ").append(currentOutputPath).append("\n");
            status.append("Combined exists: ").append(outputFile.exists()).append("\n");
            status.append("Combined size: ").append(outputFile.length()).append(" bytes\n");
        } else {
            // Separate recording mode
            status.append("Recording Mode: Separate Files\n");
            status.append("Audio Recording: ").append(isRecordingAudio ? "active" : "stopped").append("\n");
            status.append("Video Recording: ").append(isRecordingVideo ? "active" : "stopped").append("\n");

            if (audioOutputPath != null) {
                File audioFile = new File(audioOutputPath);
                status.append("Audio file: ").append(audioOutputPath).append("\n");
                status.append("Audio exists: ").append(audioFile.exists()).append("\n");
                status.append("Audio size: ").append(audioFile.length()).append(" bytes\n");
            }

            if (videoOutputPath != null) {
                File videoFile = new File(videoOutputPath);
                status.append("Video file: ").append(videoOutputPath).append("\n");
                status.append("Video exists: ").append(videoFile.exists()).append("\n");
                status.append("Video size: ").append(videoFile.length()).append(" bytes\n");
            }
        }

        status.append("Video surface: ").append(videoSurface != null ? "active" : "null").append("\n");
        status.append("Video frame generation: ").append(isGeneratingVideoFrames ? "active" : "stopped").append("\n");
        status.append("WebRTC video track: ").append(videoTrack != null ? "active" : "null").append("\n");

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
                    currentCameraFacing = attemptFacing; // Update current facing mode
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
            // Toggle the current facing mode
            currentCameraFacing = (currentCameraFacing == Facing.FRONT) ? Facing.BACK : Facing.FRONT;
            ((CameraVideoCapturer) videoCapturer).switchCamera(null);
        }
    }

    /**
     * Gets the current camera facing mode.
     *
     * @return String representation of current camera facing mode
     */
    public String getCurrentCameraFacingMode() {
        if (videoCapturer instanceof CameraVideoCapturer) {
            // Return the current facing mode as string
            return currentCameraFacing.toString();
        }
        return "unknown";
    }

    /**
     * Sets the current camera facing mode (called from React Native layer).
     *
     * @param facingMode String representation of camera facing mode (FRONT/BACK)
     */
    public void setCurrentCameraFacingMode(String facingMode) {
        try {
            Facing newFacing;
            if ("FRONT".equalsIgnoreCase(facingMode)) {
                newFacing = Facing.FRONT;
            } else if ("BACK".equalsIgnoreCase(facingMode)) {
                newFacing = Facing.BACK;
            } else {
                Log.w(TAG, "Unknown facing mode: " + facingMode);
                return;
            }
            
            if (currentCameraFacing != newFacing) {
                Log.i(TAG, "Camera facing mode changed from " + currentCameraFacing + " to " + newFacing);
                currentCameraFacing = newFacing;
                
                // If recording is active, restart video capture with new camera
                if (isRecordingToFile && mediaRecorder != null) {
                    Log.i(TAG, "Restarting video capture with new camera facing mode: " + newFacing);
                    restartVideoCaptureWithNewCamera();
                }
            } else {
                Log.i(TAG, "Camera facing mode already set to: " + currentCameraFacing);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set camera facing mode: " + facingMode, e);
        }
    }


    /**
     * Restarts video capture with the new camera facing mode during recording.
     */
    private void restartVideoCaptureWithNewCamera() {
        try {
            Log.i(TAG, "Restarting video capture with camera facing: " + currentCameraFacing);
            
            // Stop current video capture
            if (videoCapturer != null) {
                videoCapturer.stopCapture();
                videoCapturer.dispose();
            }
            
            // Create new video track with current camera facing mode
            String newTrackId = "JITSI_RECORDING_RESTARTED_" + System.currentTimeMillis();
            VideoTrack newVideoTrack = createVideoTrack(newTrackId, currentCameraFacing, 640, 480, 30);
            
            if (newVideoTrack != null) {
                Log.i(TAG, "Successfully restarted video capture with new camera: " + currentCameraFacing);
                // Update the video track reference
                videoTrack = newVideoTrack;
            } else {
                Log.e(TAG, "Failed to restart video capture with new camera");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error restarting video capture with new camera", e);
        }
    }

    /**
     * Stops camera capture and switches to black frame generation.
     */
    private void stopCameraCapture() {
        try {
            Log.i(TAG, "Stopping camera capture");
            if (videoCapturer != null) {
                videoCapturer.stopCapture();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping camera capture", e);
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
        stopVideoFrameGeneration();
        stopCombinedRecording();
        stopSeparateRecording();

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



