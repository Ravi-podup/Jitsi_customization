import { IStore } from '../../../app/types';
import { setAudioMuted } from '../../../base/media/actions';
import { NativeModules, Platform } from 'react-native';

interface ILocalRecordingManager {
    addAudioTrackToLocalRecording: (track: any) => void;
    cleanupGlobalReference: () => void;
    getCameraSwitchCallback: () => ((event: { from: string; to: string; timestamp: number }) => void) | null;
    getCurrentCameraFacingMode: () => Promise<string>;
    isRecordingLocally: () => boolean;
    isSupported: () => boolean;
    resetRecordingState: () => void;
    selfRecording: {
        on: boolean;
        withVideo: boolean;
    };
    setCameraSwitchCallback: (callback: (event: { from: string; to: string; timestamp: number }) => void) => void;
    setupCameraSwitchCallback: () => void;
    startLocalRecording: (store: IStore, onlySelf: boolean) => Promise<void>;
    stopLocalRecording: () => void;
    syncCameraStateWithNative: (facingMode: string) => void;
}

const { LocalMedia } = NativeModules as any;

let recording = false;
let storeRef: IStore | null = null;


// Camera switch event handler
let onCameraSwitchCallback: ((event: { from: string; to: string; timestamp: number }) => void) | null = null;

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
        
        // Clear the global reference to prevent issues
        this.cleanupGlobalReference();
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
            
            // Set up camera switch callback for recording
            this.setupCameraSwitchCallback();
            
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
    },

    /**
     * Sets a callback function to be called when camera is switched.
     *
     * @param {Function} callback - Function to call when camera switches
     * @returns {void}
     */
    setCameraSwitchCallback(callback: (event: { from: string; to: string; timestamp: number }) => void) {
        onCameraSwitchCallback = callback;
    },

    /**
     * Gets the current camera switch callback.
     *
     * @returns {Function|null} Current callback function or null
     */
    getCameraSwitchCallback() {
        return onCameraSwitchCallback;
    },

    /**
     * Gets the current camera facing mode from native layer.
     *
     * @returns {Promise<string>} Current camera facing mode (FRONT/BACK)
     */
    async getCurrentCameraFacingMode(): Promise<string> {
        try {
            if (LocalMedia?.getCurrentCameraFacingMode) {
                const facingMode = await LocalMedia.getCurrentCameraFacingMode();
                console.log('LocalRecordingManager: Current camera facing mode:', facingMode);
                return facingMode;
            }
            return 'unknown';
        } catch (error) {
            console.warn('LocalRecordingManager: Failed to get camera facing mode:', error);
            return 'unknown';
        }
    },

    /**
     * Sets up camera switch callback for recording.
     *
     * @returns {void}
     */
    setupCameraSwitchCallback() {
        console.log('LocalRecordingManager: Setting up camera switch callback for recording');
        
        // Set up a simple callback for logging
        this.setCameraSwitchCallback((event) => {
            console.log('LocalRecordingManager: Camera switch detected during recording:', event);
        });
    },

    /**
     * Syncs camera state with native layer when camera switch is detected.
     *
     * @param {string} facingMode - New camera facing mode (user/environment)
     * @returns {void}
     */
    syncCameraStateWithNative(facingMode: string) {
        try {
            console.log('LocalRecordingManager: syncCameraStateWithNative called with:', facingMode);
            
            // Convert Jitsi Meet facing mode to native facing mode
            let nativeFacingMode: string;
            if (facingMode === 'user') {
                nativeFacingMode = 'FRONT';
            } else if (facingMode === 'environment') {
                nativeFacingMode = 'BACK';
            } else {
                console.warn('LocalRecordingManager: Unknown facing mode:', facingMode);
                return;
            }

            console.log('LocalRecordingManager: Converting to native facing mode:', nativeFacingMode);

            // Update native layer
            if (LocalMedia?.setCurrentCameraFacingMode) {
                LocalMedia.setCurrentCameraFacingMode(nativeFacingMode);
                console.log('LocalRecordingManager: Successfully synced camera state with native:', nativeFacingMode);
            } else {
                console.warn('LocalRecordingManager: setCurrentCameraFacingMode method not available');
            }
        } catch (error) {
            console.warn('LocalRecordingManager: Failed to sync camera state:', error);
        }
    },


    /**
     * Cleans up global reference to prevent memory leaks and context issues.
     *
     * @returns {void}
     */
    cleanupGlobalReference() {
        try {
            if (typeof window !== 'undefined' && (window as any).LocalRecordingManager) {
                console.log('LocalRecordingManager: Cleaning up global reference');
                delete (window as any).LocalRecordingManager;
            }
        } catch (error) {
            console.warn('LocalRecordingManager: Failed to cleanup global reference:', error);
        }
    }

};

// Make LocalRecordingManager available globally for middleware access
if (typeof window !== 'undefined') {
    (window as any).LocalRecordingManager = LocalRecordingManager;
}

export default LocalRecordingManager;
