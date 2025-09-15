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
                console.log('LocalRecordingManager: Stopped combined audio+video recording');
                
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
        // No need to unmute since we never muted WebRTC audio during recording
        console.log('LocalRecordingManager: WebRTC audio was never muted - no unmute needed');
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
            // Don't create new video track - use existing WebRTC video track if available
            console.log('LocalRecordingManager: Will attempt to capture from existing WebRTC video track');
            console.log('LocalRecordingManager: No new camera session will be created to avoid conflicts');
            // Keep mic active during recording - both WebRTC and MediaRecorder can capture audio
            // This allows the camera to remain active and provides better user experience
            console.log('LocalRecordingManager: Keeping WebRTC audio active during recording');

            if (LocalMedia?.startRecordingToFile) {
                await LocalMedia.startRecordingToFile();
                console.log('LocalRecordingManager: Started combined audio+video recording in single file');
                LocalRecordingManager.selfRecording.withVideo = true;
                
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
