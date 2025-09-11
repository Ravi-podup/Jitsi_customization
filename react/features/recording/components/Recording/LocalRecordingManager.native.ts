import { IStore } from '../../../app/types';
import { setAudioMuted } from '../../../base/media/actions';
import { NativeModules, Platform } from 'react-native';

interface ILocalRecordingManager {
    addAudioTrackToLocalRecording: (track: any) => void;
    isRecordingLocally: () => boolean;
    isSupported: () => boolean;
    resetRecordingState: () => void;
    selfRecording: {
        on: boolean;
        withVideo: boolean;
    };
    startLocalRecording: (store: IStore, onlySelf: boolean) => Promise<void>;
    stopLocalRecording: () => void;
}

const { LocalMedia } = NativeModules as any;

let recording = false;
let storeRef: IStore | null = null;

const LocalRecordingManager: ILocalRecordingManager = {
    selfRecording: {
        on: false,
        withVideo: false
    },

    /**
     * Adds audio track to the recording stream.
     *
     * @param {any} track - Track to be added,.
     * @returns {void}
     */
    addAudioTrackToLocalRecording() { }, // Not used in native path; handled by LocalMedia

    /**
     * Stops local recording.
     *
     * @returns {void}
     * */
    async stopLocalRecording() {
        if (!recording) {
            return;
        }
        try {
            if (LocalMedia?.stopVideo) {
                LocalMedia.stopVideo();
            }
            if (LocalMedia?.stopRecordingToFile) {
                await LocalMedia.stopRecordingToFile();
                console.log('LocalRecordingManager: Stopped recording to device storage');
                
                // Get the file paths
                if (LocalMedia?.getRecordingFilePaths) {
                    const filePaths = await LocalMedia.getRecordingFilePaths();
                    console.log('LocalRecordingManager: Recording files saved to:', filePaths);
                }
                
                // Get final recording status
                if (LocalMedia?.getRecordingStatus) {
                    const status = await LocalMedia.getRecordingStatus();
                    console.log('LocalRecordingManager: Final recording status:', status);
                }
                
                // List all files in the recordings directory
                if (LocalMedia?.listRecordingFiles) {
                    const fileList = await LocalMedia.listRecordingFiles();
                    console.log('LocalRecordingManager: Files in recordings directory:', fileList);
                }
            }
            if (LocalMedia?.dispose) {
                LocalMedia.dispose();
            }
        } catch (e) {
            // swallow
        }
        // Unmute after recording ends
        try {
            if (storeRef) {
                await storeRef.dispatch<any>(setAudioMuted(false, true));
            }
        } catch (e) {
            console.warn('Failed to unmute after local recording', e);
        }
        recording = false;
        LocalRecordingManager.selfRecording.on = false;
        LocalRecordingManager.selfRecording.withVideo = false;
    },

    /**
     * Starts a local recording.
     *
     * @param {IStore} store - The Redux store.
     * @returns {void}
     */
    async startLocalRecording(_store: IStore, onlySelf: boolean) {
        console.log('start local recording');
        storeRef = _store;
        
        // Start mic by default; optionally start camera as well.
        if (Platform.OS !== 'android') {
            return;
        }
        if (recording) {
            return;
        }
        try {
            if (LocalMedia?.initialize) {
                await LocalMedia.initialize();
            }
            
            // Test file writing first
            if (LocalMedia?.testFileWriting) {
                await LocalMedia.testFileWriting();
                console.log('LocalRecordingManager: Test file writing completed');
            }
            if (LocalMedia?.startAudio) {
                await LocalMedia.startAudio();
            }
            // Try to start camera (front) with safe defaults so video is active too
            try {
                if (LocalMedia?.startVideo) {
                    await LocalMedia.startVideo('front', 640, 480, 15);
                    LocalRecordingManager.selfRecording.withVideo = true;
                }
            } catch (e) {
                console.warn('Camera start failed; continuing audio-only', e);
                LocalRecordingManager.selfRecording.withVideo = false;
            }
            // Mute mic in the call during local recording so MediaRecorder can capture exclusively
            try {
                await _store.dispatch<any>(setAudioMuted(true, true));
            } catch (e) {
                console.warn('Failed to mute before local recording', e);
            }

            if (LocalMedia?.startRecordingToFile) {
                await LocalMedia.startRecordingToFile();
                console.log('LocalRecordingManager: Started recording to device storage');
                
                // Write some test data to verify file writing works
                if (LocalMedia?.writeRecordingData) {
                    await LocalMedia.writeRecordingData('Test recording data - audio track created\n');
                    console.log('LocalRecordingManager: Wrote test data after audio track creation');
                }
                
                // Check recording status for debugging
                if (LocalMedia?.getRecordingStatus) {
                    const status = await LocalMedia.getRecordingStatus();
                    console.log('LocalRecordingManager: Recording status:', status);
                }
                
                // Also check status after a short delay to see if files are being written
                setTimeout(async () => {
                    if (LocalMedia?.getRecordingStatus) {
                        const status = await LocalMedia.getRecordingStatus();
                        console.log('LocalRecordingManager: Recording status after delay:', status);
                    }
                }, 2000);
            }
            // Skip video recording entirely for now since camera is disabled
            console.log("Audio-only recording (camera disabled)");
            LocalRecordingManager.selfRecording.withVideo = false;
            recording = true;
            LocalRecordingManager.selfRecording.on = true;
        } catch (e) {
            // eslint-disable-next-line no-console
            console.warn('Local recording start failed', e);
            recording = false;
            LocalRecordingManager.selfRecording.on = false;
            LocalRecordingManager.selfRecording.withVideo = false;
        }
    },

    /**
     * Whether or not local recording is supported.
     *
     * @returns {boolean}
     */
    isSupported() {
        return Platform.OS === 'android' && !!LocalMedia;
    },

    /**
     * Whether or not we're currently recording locally.
     *
     * @returns {boolean}
     */
    isRecordingLocally() {
        return recording;
    },

    /**
     * Resets the recording state to initial values.
     * Call this when the app starts or when you need to clear the state.
     *
     * @returns {void}
     */
    resetRecordingState() {
        console.log('LocalRecordingManager: Resetting recording state');
        recording = false;
        LocalRecordingManager.selfRecording.on = false;
        LocalRecordingManager.selfRecording.withVideo = false;
    }

};

export default LocalRecordingManager;
