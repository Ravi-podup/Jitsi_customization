import { IStore } from '../../../app/types';
import { setAudioMuted } from '../../../base/media/actions';
import { NativeModules, Platform } from 'react-native';
import { startCountdown, stopCountdown } from '../../../countdown/actions';

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
    _startActualRecording: (store: IStore, onlySelf: boolean) => Promise<void>;
    stopLocalRecording: () => void;
    syncCameraStateWithNative: (facingMode: string) => void;
    getRecordingsDirectoryPath: () => Promise<string>;
    listRecordingFiles: () => Promise<any[]>;
    readRecordingFile: (filePath: string) => Promise<string>;
    setRecordingMode: (mode: string) => Promise<boolean>;
    startScreenRecording: () => Promise<boolean>;
    exportRecordingToPhotos: (filePath: string) => Promise<boolean>;
    shareRecordingFile: (filePath: string) => Promise<boolean>;
    cleanupOldRecordings: (maxAge: number) => Promise<number>;
    exportToDocuments: (filePath: string) => Promise<boolean>;
    showRecordingsLocation: () => Promise<string>;
    checkVideoFile: (filePath: string) => Promise<{
        exists: boolean;
        size: number;
        modified: number;
        isPlayable: boolean;
        duration: number;
        videoTracks: number;
        audioTracks: number;
        fileSizeKB: number;
        error?: string;
    }>;
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
        
        // Stop any active countdown
        if (storeRef?.dispatch) {
            try {
                storeRef.dispatch(stopCountdown());
            } catch (error) {
                console.warn('LocalRecordingManager: Error stopping countdown:', error);
            }
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
        if (Platform.OS !== 'android' && Platform.OS !== 'ios') {
            return;
        }
        if (recording) {
            return;
        }

        // Show countdown on both Android and iOS before starting recording
        console.log(`LocalRecordingManager: Starting countdown for ${Platform.OS}`);
        try {
            _store.dispatch(startCountdown(() => {
                console.log('LocalRecordingManager: Countdown completed, starting recording');
                // Use setTimeout to ensure countdown UI is fully dismissed before starting recording
                setTimeout(() => {
                    try {
                        this._startActualRecording(_store, onlySelf);
                    } catch (error) {
                        console.error('LocalRecordingManager: Error starting recording after countdown:', error);
                    }
                }, 100); // Small delay to ensure UI cleanup
            }));
        } catch (error) {
            console.error('LocalRecordingManager: Error starting countdown:', error);
            // Fallback: start recording immediately if countdown fails
            this._startActualRecording(_store, onlySelf);
        }
    },

    /**
     * Actually starts the recording after countdown completes.
     *
     * @param {IStore} store - The Redux store.
     * @param {boolean} onlySelf - Whether to only record the local streams.
     * @returns {void}
     */
    async _startActualRecording(_store: IStore, onlySelf: boolean) {
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
            
            // Skip microphone state check for now - simplified recording
            console.log('LocalRecordingManager: Starting recording without microphone state check');
            
            // For now, we'll always use audio-only recording mode
            console.log('LocalRecordingManager: Starting audio-only recording');
            if (LocalMedia?.setRecordingMode) {
                await LocalMedia.setRecordingMode('audio-only');
            }
            
            // For audio-only recording, we don't need to call startAudio separately
            // as the native layer will handle the audio capture session
            console.log('LocalRecordingManager: Audio-only recording mode - native layer will handle audio capture');
            
            // Don't create new video track - use existing WebRTC video track if available
            console.log('LocalRecordingManager: Will attempt to capture from existing WebRTC video track');
            console.log('LocalRecordingManager: No new camera session will be created to avoid conflicts');
            // Keep mic active during recording - both WebRTC and MediaRecorder can capture audio
            // This allows the camera to remain active and provides better user experience
            console.log('LocalRecordingManager: Keeping WebRTC audio active during recording');

            if (LocalMedia?.startRecordingToFile) {
                try {
                    console.log('LocalRecordingManager: About to call startRecordingToFile');
                    await LocalMedia.startRecordingToFile();
                    console.log('LocalRecordingManager: Started audio-only recording successfully');
                    LocalRecordingManager.selfRecording.withVideo = false; // Audio-only recording
                
                // Check recording status for debugging
                if (LocalMedia?.getRecordingStatus) {
                    const status = await LocalMedia.getRecordingStatus();
                    console.log('LocalRecordingManager: Recording status:', status);
                }
                } catch (error) {
                    console.error('LocalRecordingManager: Failed to start recording:', error);
                    throw error; // Re-throw to be caught by the outer try-catch
                }
                
                // Also check status after a short delay to see if files are being written
                setTimeout(async () => {
                    if (LocalMedia?.getRecordingStatus) {
                        const status = await LocalMedia.getRecordingStatus();
                        console.log('LocalRecordingManager: Recording status after delay:', status);
                    }
                }, 2000);
            } else {
                console.warn('LocalRecordingManager: startRecordingToFile not available');
                throw new Error('startRecordingToFile method not available');
            }
            
            // Set recording state BEFORE the try-catch block ends
            recording = true;
            LocalRecordingManager.selfRecording.on = true;
            
            console.log('LocalRecordingManager: Recording state updated:', {
                recording,
                selfRecordingOn: LocalRecordingManager.selfRecording.on,
                selfRecordingWithVideo: LocalRecordingManager.selfRecording.withVideo
            });
        } catch (e) {
            // eslint-disable-next-line no-console
            console.warn('Local recording start failed', e);
            console.error('LocalRecordingManager: Error details:', {
                error: e,
                message: (e as Error)?.message,
                stack: (e as Error)?.stack
            });
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
        const supported = (Platform.OS === 'android' || Platform.OS === 'ios') && !!LocalMedia;
        console.log('LocalRecordingManager: isSupported check', {
            platform: Platform.OS,
            hasLocalMedia: !!LocalMedia,
            supported
        });
        return supported;
    },

    /**
     * Whether or not we're currently recording locally.
     *
     * @returns {boolean}
     */
    isRecordingLocally() {
        console.log('LocalRecordingManager: isRecordingLocally called, returning:', recording);
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
        
        // Stop any active countdown
        if (storeRef?.dispatch) {
            try {
                storeRef.dispatch(stopCountdown());
            } catch (error) {
                console.warn('LocalRecordingManager: Error stopping countdown during reset:', error);
            }
        }
        
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
    },

    /**
     * Gets the recordings directory path.
     *
     * @returns {Promise<string>}
     */
    async getRecordingsDirectoryPath(): Promise<string> {
        try {
            if (LocalMedia?.getRecordingsDirectoryPath) {
                return await LocalMedia.getRecordingsDirectoryPath();
            }
            return '';
        } catch (error) {
            console.warn('LocalRecordingManager: Failed to get recordings directory path:', error);
            return '';
        }
    },

    /**
     * Lists all recording files.
     *
     * @returns {Promise<any[]>}
     */
    async listRecordingFiles(): Promise<any[]> {
        try {
            if (LocalMedia?.listRecordingFiles) {
                return await LocalMedia.listRecordingFiles();
            }
            return [];
        } catch (error) {
            console.warn('LocalRecordingManager: Failed to list recording files:', error);
            return [];
        }
    },

    /**
     * Reads a recording file.
     *
     * @param {string} filePath - Path to the file to read.
     * @returns {Promise<string>}
     */
    async readRecordingFile(filePath: string): Promise<string> {
        try {
            if (LocalMedia?.readRecordingFile) {
                return await LocalMedia.readRecordingFile(filePath);
            }
            return '';
        } catch (error) {
            console.warn('LocalRecordingManager: Failed to read recording file:', error);
            return '';
        }
    },

    /**
     * Sets the recording mode.
     *
     * @param {string} mode - Recording mode ('real-video', 'file-based', 'screen-recording').
     * @returns {Promise<boolean>}
     */
    async setRecordingMode(mode: string): Promise<boolean> {
        try {
            if (LocalMedia?.setRecordingMode) {
                return await LocalMedia.setRecordingMode(mode);
            }
            return false;
        } catch (error) {
            console.warn('LocalRecordingManager: Failed to set recording mode:', error);
            return false;
        }
    },

    /**
     * Starts screen recording.
     *
     * @returns {Promise<boolean>}
     */
    async startScreenRecording(): Promise<boolean> {
        try {
            if (LocalMedia?.startScreenRecording) {
                return await LocalMedia.startScreenRecording();
            }
            return false;
        } catch (error) {
            console.warn('LocalRecordingManager: Failed to start screen recording:', error);
            return false;
        }
    },

    /**
     * Exports recording to photos.
     *
     * @param {string} filePath - Path to the file to export.
     * @returns {Promise<boolean>}
     */
    async exportRecordingToPhotos(filePath: string): Promise<boolean> {
        try {
            if (LocalMedia?.exportRecordingToPhotos) {
                return await LocalMedia.exportRecordingToPhotos(filePath);
            }
            return false;
        } catch (error) {
            console.warn('LocalRecordingManager: Failed to export recording to photos:', error);
            return false;
        }
    },

    /**
     * Shares a recording file.
     *
     * @param {string} filePath - Path to the file to share.
     * @returns {Promise<boolean>}
     */
    async shareRecordingFile(filePath: string): Promise<boolean> {
        try {
            if (LocalMedia?.shareRecordingFile) {
                return await LocalMedia.shareRecordingFile(filePath);
            }
            return false;
        } catch (error) {
            console.warn('LocalRecordingManager: Failed to share recording file:', error);
            return false;
        }
    },

    /**
     * Cleans up old recordings.
     *
     * @param {number} maxAge - Maximum age in seconds.
     * @returns {Promise<number>}
     */
    async cleanupOldRecordings(maxAge: number): Promise<number> {
        try {
            if (LocalMedia?.cleanupOldRecordings) {
                return await LocalMedia.cleanupOldRecordings(maxAge);
            }
            return 0;
        } catch (error) {
            console.warn('LocalRecordingManager: Failed to cleanup old recordings:', error);
            return 0;
        }
    },

    /**
     * Exports recording to documents.
     *
     * @param {string} filePath - Path to the file to export.
     * @returns {Promise<boolean>}
     */
    async exportToDocuments(filePath: string): Promise<boolean> {
        try {
            if (LocalMedia?.exportToDocuments) {
                return await LocalMedia.exportToDocuments(filePath);
            }
            return false;
        } catch (error) {
            console.warn('LocalRecordingManager: Failed to export to documents:', error);
            return false;
        }
    },

    async showRecordingsLocation(): Promise<string> {
        try {
            if (LocalMedia?.showRecordingsLocation) {
                return await LocalMedia.showRecordingsLocation();
            }
            return 'LocalMedia module not available';
        } catch (error) {
            console.warn('LocalRecordingManager: Failed to show recordings location:', error);
            return 'Error getting recordings location';
        }
    },

    async checkVideoFile(filePath: string): Promise<{
        exists: boolean;
        size: number;
        modified: number;
        isPlayable: boolean;
        duration: number;
        videoTracks: number;
        audioTracks: number;
        fileSizeKB: number;
        error?: string;
    }> {
        try {
            if (LocalMedia?.checkVideoFile) {
                return await LocalMedia.checkVideoFile(filePath);
            }
            return {
                exists: false,
                size: 0,
                modified: 0,
                isPlayable: false,
                duration: 0,
                videoTracks: 0,
                audioTracks: 0,
                fileSizeKB: 0,
                error: 'LocalMedia module not available'
            };
        } catch (error) {
            console.warn('LocalRecordingManager: Failed to check video file:', error);
            return {
                exists: false,
                size: 0,
                modified: 0,
                isPlayable: false,
                duration: 0,
                videoTracks: 0,
                audioTracks: 0,
                fileSizeKB: 0,
                error: 'Error checking video file'
            };
        }
    }

};

// Make LocalRecordingManager available globally for middleware access
if (typeof window !== 'undefined') {
    (window as any).LocalRecordingManager = LocalRecordingManager;
}

export default LocalRecordingManager;
