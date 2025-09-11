package org.jitsi.meet.sdk;

import android.Manifest;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.module.annotations.ReactModule;

import org.webrtc.AudioTrack;
import org.webrtc.VideoTrack;

@ReactModule(name = LocalMediaModule.NAME)
class LocalMediaModule extends ReactContextBaseJavaModule {
    static final String NAME = "LocalMedia";

    LocalMediaModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @ReactMethod
    public void initialize(Promise promise) {
        try {
            LocalMediaTracks.getInstance().initialize(getReactApplicationContext());
            promise.resolve(null);
        } catch (Throwable t) {
            promise.reject("initialize", t);
        }
    }

    @ReactMethod
    public void startAudio(Promise promise) {
        try {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                promise.reject("permission", "RECORD_AUDIO not granted");
                return;
            }
            AudioTrack track = LocalMediaTracks.getInstance().createAudioTrack("JITSI_LOCAL_AUDIO");
            promise.resolve(true);
        } catch (Throwable t) {
            promise.reject("startAudio", t);
        }
    }

    @ReactMethod
    public void startVideo(String facing, int width, int height, int fps, Promise promise) {
        try {
            if (!hasPermission(Manifest.permission.CAMERA)) {
                promise.reject("permission", "CAMERA not granted");
                return;
            }
            LocalMediaTracks.Facing f = "back".equalsIgnoreCase(facing)
                    ? LocalMediaTracks.Facing.BACK : LocalMediaTracks.Facing.FRONT;
            VideoTrack track = LocalMediaTracks.getInstance().createVideoTrack(
                    "JITSI_LOCAL_VIDEO", f, width, height, fps);
            promise.resolve(true);
        } catch (Throwable t) {
            promise.reject("startVideo", t);
        }
    }

    @ReactMethod
    public void switchCamera() {
        LocalMediaTracks.getInstance().switchCamera();
    }

    @ReactMethod
    public void stopVideo() {
        LocalMediaTracks.getInstance().stopVideoCapture();
    }

    @ReactMethod
    public void dispose() {
        LocalMediaTracks.getInstance().dispose();
    }

    @ReactMethod
    public void startRecordingToFile(Promise promise) {
        try {
            LocalMediaTracks.getInstance().startRecordingToFile();
            promise.resolve(true);
        } catch (Throwable t) {
            promise.reject("startRecordingToFile", t);
        }
    }

    @ReactMethod
    public void stopRecordingToFile(Promise promise) {
        try {
            LocalMediaTracks.getInstance().stopRecordingToFile();
            promise.resolve(true);
        } catch (Throwable t) {
            promise.reject("stopRecordingToFile", t);
        }
    }

    @ReactMethod
    public void getRecordingFilePaths(Promise promise) {
        try {
            String[] paths = LocalMediaTracks.getInstance().getRecordingFilePaths();
            promise.resolve(paths);
        } catch (Throwable t) {
            promise.reject("getRecordingFilePaths", t);
        }
    }

    @ReactMethod
    public void writeRecordingData(String data, Promise promise) {
        try {
            LocalMediaTracks.getInstance().writeRecordingData(data);
            promise.resolve(true);
        } catch (Throwable t) {
            promise.reject("writeRecordingData", t);
        }
    }

    @ReactMethod
    public void getRecordingStatus(Promise promise) {
        try {
            String status = LocalMediaTracks.getInstance().getRecordingStatus();
            promise.resolve(status);
        } catch (Throwable t) {
            promise.reject("getRecordingStatus", t);
        }
    }

    // --- AAC (M4A) recording and auto-play ---
    @ReactMethod
    public void startAacRecording(Promise promise) {
        try {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                promise.reject("permission", "RECORD_AUDIO not granted");
                return;
            }
            LocalMediaTracks.getInstance().startAacRecording();
            promise.resolve(true);
        } catch (Throwable t) {
            promise.reject("startAacRecording", t);
        }
    }

    @ReactMethod
    public void stopAacRecordingAndPlay(Promise promise) {
        try {
            LocalMediaTracks.getInstance().stopAacRecordingAndPlay();
            promise.resolve(true);
        } catch (Throwable t) {
            promise.reject("stopAacRecordingAndPlay", t);
        }
    }

    @ReactMethod
    public void testFileWriting(Promise promise) {
        try {
            LocalMediaTracks.getInstance().testFileWriting();
            promise.resolve(true);
        } catch (Throwable t) {
            promise.reject("testFileWriting", t);
        }
    }

    @ReactMethod
    public void listRecordingFiles(Promise promise) {
        try {
            String fileList = LocalMediaTracks.getInstance().listRecordingFiles();
            promise.resolve(fileList);
        } catch (Throwable t) {
            promise.reject("listRecordingFiles", t);
        }
    }

    private boolean hasPermission(String perm) {
        return ContextCompat.checkSelfPermission(getReactApplicationContext(), perm)
                == PackageManager.PERMISSION_GRANTED;
    }
}



