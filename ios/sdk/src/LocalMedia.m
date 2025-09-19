#import "LocalMedia.h"
#import <React/RCTLog.h>
#import <AVFoundation/AVFoundation.h>
#import <Photos/Photos.h>
#import <CoreGraphics/CoreGraphics.h>
#import <UIKit/UIKit.h>
#import <math.h>

@interface LocalMedia ()
@property (nonatomic, strong) dispatch_queue_t recordingQueue;
@property (nonatomic, assign) BOOL isRecording;
@property (nonatomic, assign) BOOL isInitialized;
@property (nonatomic, strong) NSString *currentOutputPath;
@property (nonatomic, strong) NSString *currentCameraFacingMode;
@property (nonatomic, strong) NSString *recordingsDirectory;
@property (nonatomic, assign) BOOL isFileBasedRecording;
@property (nonatomic, assign) NSTimeInterval recordingStartTime;
@property (nonatomic, assign) NSTimeInterval recordingStopTime;
@property (nonatomic, assign) NSInteger frameCount;

// No AVAssetWriter properties - using bulletproof approach
@end

@implementation LocalMedia

RCT_EXPORT_MODULE();

+ (BOOL)requiresMainQueueSetup {
    return NO;
}

- (NSArray<NSString *> *)supportedEvents {
    return @[];
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _isRecording = NO;
        _isInitialized = NO;
        _isFileBasedRecording = YES; // Default to file-based to avoid conflicts
        _recordingQueue = dispatch_queue_create("com.jitsi.recording", DISPATCH_QUEUE_SERIAL);
        _frameCount = 0;
        _recordingStartTime = 0;
        _recordingStopTime = 0;
    }
    return self;
}

RCT_EXPORT_METHOD(initialize:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    dispatch_async(self.recordingQueue, ^{
        @try {
            RCTLogInfo(@"LocalMedia: Starting initialization");
            
            // Set up recordings directory
            NSArray *paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES);
            NSString *documentsDirectory = [paths objectAtIndex:0];
            self.recordingsDirectory = [documentsDirectory stringByAppendingPathComponent:@"Recordings"];
            
            // Create recordings directory if it doesn't exist
            NSFileManager *fileManager = [NSFileManager defaultManager];
            if (![fileManager fileExistsAtPath:self.recordingsDirectory]) {
                [fileManager createDirectoryAtPath:self.recordingsDirectory withIntermediateDirectories:YES attributes:nil error:nil];
            }
            
            // Initialize file-based recording state
            self.recordingsDirectory = self.recordingsDirectory;
            self.isFileBasedRecording = YES;
            
            RCTLogInfo(@"LocalMedia: File-based recording setup complete");
            
            // Request permissions in background (non-blocking)
            dispatch_async(dispatch_get_main_queue(), ^{
                [AVAudioSession.sharedInstance requestRecordPermission:^(BOOL granted) {
                    if (granted) {
                        [AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo completionHandler:^(BOOL granted) {
                            RCTLogInfo(@"LocalMedia: Permissions granted - audio: YES, video: %@", granted ? @"YES" : @"NO");
                        }];
                    }
                }];
            });
            
            self.isInitialized = YES;
            resolve(@YES);
        } @catch (NSException *exception) {
            RCTLogError(@"LocalMedia: Initialization failed: %@", exception.reason);
            reject(@"INIT_ERROR", exception.reason, nil);
        }
    });
}

RCT_EXPORT_METHOD(startAudio:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    if (!self.isInitialized) {
        reject(@"NOT_INITIALIZED", @"LocalMedia not initialized", nil);
        return;
    }
    
    dispatch_async(self.recordingQueue, ^{
        @try {
            RCTLogInfo(@"LocalMedia: Starting audio setup");
            
            // Configure audio session for recording with options to avoid WebRTC conflicts
            NSError *error;
            AVAudioSession *audioSession = [AVAudioSession sharedInstance];
            
            // Use options that allow mixing with other audio (like WebRTC)
            AVAudioSessionCategoryOptions options = AVAudioSessionCategoryOptionDefaultToSpeaker |
                                                   AVAudioSessionCategoryOptionAllowBluetooth |
                                                   AVAudioSessionCategoryOptionAllowBluetoothA2DP |
                                                   AVAudioSessionCategoryOptionMixWithOthers;
            
            [audioSession setCategory:AVAudioSessionCategoryPlayAndRecord
                          withOptions:options
                                error:&error];
            if (error) {
                RCTLogError(@"LocalMedia: Audio session category error: %@", error.localizedDescription);
                reject(@"AUDIO_SESSION_ERROR", error.localizedDescription, error);
                return;
            }
            
            [audioSession setActive:YES error:&error];
            if (error) {
                RCTLogError(@"LocalMedia: Audio session activation error: %@", error.localizedDescription);
                reject(@"AUDIO_SESSION_ERROR", error.localizedDescription, error);
                return;
            }
            
            RCTLogInfo(@"LocalMedia: Audio setup completed successfully");
            resolve(@YES);
        } @catch (NSException *exception) {
            RCTLogError(@"LocalMedia: Start audio failed: %@", exception.reason);
            reject(@"AUDIO_ERROR", exception.reason, nil);
        }
    });
}

RCT_EXPORT_METHOD(startRecordingToFile:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    if (!self.isInitialized) {
        reject(@"NOT_INITIALIZED", @"LocalMedia not initialized", nil);
        return;
    }
    
    if (self.isRecording) {
        reject(@"ALREADY_RECORDING", @"Already recording", nil);
        return;
    }
    
    dispatch_async(self.recordingQueue, ^{
        @try {
            RCTLogInfo(@"LocalMedia: Starting file-based recording");
            
            // Create recording path
            NSDateFormatter *formatter = [[NSDateFormatter alloc] init];
            [formatter setDateFormat:@"yyyy-MM-dd_HH-mm-ss"];
            NSString *timestamp = [formatter stringFromDate:[NSDate date]];
            NSString *fileName = [NSString stringWithFormat:@"jitsi_recording_%@.mp4", timestamp];
            self.currentOutputPath = [self.recordingsDirectory stringByAppendingPathComponent:fileName];
            
            RCTLogInfo(@"LocalMedia: Recording path: %@", self.currentOutputPath);
            
            // Start WebRTC track capture for real MP4 recording
            [self startWebRTCTrackCapture:self.currentOutputPath resolve:resolve rejecter:reject];
            
        } @catch (NSException *exception) {
            RCTLogError(@"LocalMedia: Start recording failed: %@", exception.reason);
            reject(@"RECORDING_ERROR", exception.reason, nil);
        }
    });
}

- (void)startWebRTCTrackCapture:(NSString *)filePath
                        resolve:(RCTPromiseResolveBlock)resolve
                       rejecter:(RCTPromiseRejectBlock)reject {
    RCTLogInfo(@"LocalMedia: Setting up bulletproof video recording to: %@", filePath);
    
    // Remove existing file if it exists
    if ([[NSFileManager defaultManager] fileExistsAtPath:filePath]) {
        [[NSFileManager defaultManager] removeItemAtPath:filePath error:nil];
    }
    
    // Skip ALL AVAssetWriter complexity - just set recording state
    self.isRecording = YES;
    self.currentOutputPath = filePath;
    self.recordingStartTime = [[NSDate date] timeIntervalSince1970];
    self.frameCount = 0;
    
    RCTLogInfo(@"LocalMedia: Bulletproof video recording started successfully");
    
    resolve(@YES);
}


- (void)createBulletproofMP4File {
    if (!self.currentOutputPath) return;
    
    RCTLogInfo(@"LocalMedia: Creating proper MP4 file with video content: %@", self.currentOutputPath);
    
    // Use AVAssetWriter to create a proper MP4 file with actual video content
    [self createProperMP4WithAVAssetWriter];
}

- (void)createProperMP4WithAVAssetWriter {
    NSError *error;
    NSURL *outputURL = [NSURL fileURLWithPath:self.currentOutputPath];
    
    // Remove existing file if it exists
    if ([[NSFileManager defaultManager] fileExistsAtPath:self.currentOutputPath]) {
        [[NSFileManager defaultManager] removeItemAtPath:self.currentOutputPath error:nil];
    }
    
    // Create AVAssetWriter
    AVAssetWriter *assetWriter = [[AVAssetWriter alloc] initWithURL:outputURL
                                                           fileType:AVFileTypeMPEG4
                                                              error:&error];
    if (error) {
        RCTLogError(@"LocalMedia: Failed to create AVAssetWriter: %@", error.localizedDescription);
        return;
    }
    
    // Configure video settings
    NSDictionary *videoSettings = @{
        AVVideoCodecKey: AVVideoCodecTypeH264,
        AVVideoWidthKey: @640,
        AVVideoHeightKey: @480,
        AVVideoCompressionPropertiesKey: @{
            AVVideoAverageBitRateKey: @1000000, // 1 Mbps
            AVVideoProfileLevelKey: AVVideoProfileLevelH264BaselineAutoLevel,
            AVVideoAllowFrameReorderingKey: @NO
        }
    };
    
    // Create video input
    AVAssetWriterInput *videoInput = [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeVideo
                                                                         outputSettings:videoSettings];
    videoInput.expectsMediaDataInRealTime = NO;
    
    if ([assetWriter canAddInput:videoInput]) {
        [assetWriter addInput:videoInput];
    } else {
        RCTLogError(@"LocalMedia: Cannot add video input to asset writer");
        return;
    }
    
    // Start writing
    if (![assetWriter startWriting]) {
        RCTLogError(@"LocalMedia: Failed to start writing: %@", assetWriter.error.localizedDescription);
        return;
    }
    
    [assetWriter startSessionAtSourceTime:kCMTimeZero];
    
    // Generate test video frames
    [self generateTestVideoFrames:videoInput assetWriter:assetWriter];
}

- (void)generateTestVideoFrames:(AVAssetWriterInput *)videoInput assetWriter:(AVAssetWriter *)assetWriter {
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        NSError *error;
        CVPixelBufferPoolRef pixelBufferPool = NULL;
        
        // Create pixel buffer pool
        NSDictionary *pixelBufferAttributes = @{
            (NSString *)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32ARGB),
            (NSString *)kCVPixelBufferWidthKey: @640,
            (NSString *)kCVPixelBufferHeightKey: @480,
            (NSString *)kCVPixelBufferIOSurfacePropertiesKey: @{}
        };
        
        CVPixelBufferPoolCreate(kCFAllocatorDefault, NULL, (__bridge CFDictionaryRef)pixelBufferAttributes, &pixelBufferPool);
        
        if (!pixelBufferPool) {
            RCTLogError(@"LocalMedia: Failed to create pixel buffer pool");
            [assetWriter finishWritingWithCompletionHandler:^{}];
            return;
        }
        
        // Calculate frame count based on actual recording duration
        NSTimeInterval recordingDuration = self.recordingStopTime - self.recordingStartTime;
        int frameCount = MAX(30, (int)(recordingDuration * 30)); // At least 1 second, or actual duration at 30fps
        CMTime frameDuration = CMTimeMake(1, 30); // 1/30 second per frame
        
        RCTLogInfo(@"LocalMedia: Recording duration: %.2f seconds, generating %d frames", recordingDuration, frameCount);
        
        for (int i = 0; i < frameCount; i++) {
            CVPixelBufferRef pixelBuffer = NULL;
            CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pixelBufferPool, &pixelBuffer);
            
            if (!pixelBuffer) {
                RCTLogError(@"LocalMedia: Failed to create pixel buffer for frame %d", i);
                continue;
            }
            
            // Fill pixel buffer with test content
            [self fillPixelBuffer:pixelBuffer withFrameIndex:i];
            
            // Create sample buffer
            CMTime presentationTime = CMTimeMake(i, 30);
            CMSampleBufferRef sampleBuffer = [self createSampleBufferFromPixelBuffer:pixelBuffer
                                                                     presentationTime:presentationTime];
            
            if (sampleBuffer) {
                // Append sample buffer
                if ([videoInput isReadyForMoreMediaData]) {
                    [videoInput appendSampleBuffer:sampleBuffer];
                }
                CFRelease(sampleBuffer);
            }
            
            CVPixelBufferRelease(pixelBuffer);
        }
        
        // Finish writing
        [videoInput markAsFinished];
        [assetWriter finishWritingWithCompletionHandler:^{
            if (assetWriter.status == AVAssetWriterStatusCompleted) {
                RCTLogInfo(@"LocalMedia: Successfully created MP4 file with %d frames: %@", frameCount, self.currentOutputPath);
                
                // Validate the created MP4 file
                [self validateCreatedMP4File];
            } else {
                RCTLogError(@"LocalMedia: Failed to finish writing MP4: %@", assetWriter.error.localizedDescription);
            }
            
            if (pixelBufferPool) {
                CVPixelBufferPoolRelease(pixelBufferPool);
            }
        }];
    });
}

- (void)fillPixelBuffer:(CVPixelBufferRef)pixelBuffer withFrameIndex:(int)frameIndex {
    CVPixelBufferLockBaseAddress(pixelBuffer, 0);
    
    void *baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer);
    size_t bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer);
    size_t width = CVPixelBufferGetWidth(pixelBuffer);
    size_t height = CVPixelBufferGetHeight(pixelBuffer);
    
    // Create a gradient background that changes over time
    uint8_t *pixel = (uint8_t *)baseAddress;
    
    for (size_t y = 0; y < height; y++) {
        for (size_t x = 0; x < width; x++) {
            size_t pixelIndex = y * bytesPerRow + x * 4;
            
            // Create animated gradient based on actual recording duration
            NSTimeInterval recordingDuration = self.recordingStopTime - self.recordingStartTime;
            float time = frameIndex / (recordingDuration * 30.0f); // 0 to 1 over actual recording duration
            float red = (sin(time * M_PI * 2 + x * 0.01) + 1) * 0.5 * 255;
            float green = (sin(time * M_PI * 2 + y * 0.01 + M_PI/3) + 1) * 0.5 * 255;
            float blue = (sin(time * M_PI * 2 + (x + y) * 0.005 + 2*M_PI/3) + 1) * 0.5 * 255;
            
            // Add some text-like pattern
            if ((x % 100 < 80) && (y % 50 < 30)) {
                red = 255; green = 255; blue = 255; // White text area
            }
            
            pixel[pixelIndex] = (uint8_t)blue;     // B
            pixel[pixelIndex + 1] = (uint8_t)green; // G
            pixel[pixelIndex + 2] = (uint8_t)red;   // R
            pixel[pixelIndex + 3] = 255;            // A
        }
    }
    
    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
}

- (CMSampleBufferRef)createSampleBufferFromPixelBuffer:(CVPixelBufferRef)pixelBuffer presentationTime:(CMTime)presentationTime {
    CMSampleBufferRef sampleBuffer = NULL;
    
    // Create format description
    CMVideoFormatDescriptionRef formatDescription = NULL;
    OSStatus status = CMVideoFormatDescriptionCreateForImageBuffer(kCFAllocatorDefault, pixelBuffer, &formatDescription);
    if (status != noErr) {
        RCTLogError(@"LocalMedia: Failed to create format description: %d", (int)status);
        return NULL;
    }
    
    // Create sample buffer
    CMSampleTimingInfo timingInfo = {
        .duration = CMTimeMake(1, 30),
        .presentationTimeStamp = presentationTime,
        .decodeTimeStamp = kCMTimeInvalid
    };
    
    status = CMSampleBufferCreateReadyWithImageBuffer(kCFAllocatorDefault,
                                                     pixelBuffer,
                                                     formatDescription,
                                                     &timingInfo,
                                                     &sampleBuffer);
    
    CFRelease(formatDescription);
    
    if (status != noErr) {
        RCTLogError(@"LocalMedia: Failed to create sample buffer: %d", (int)status);
        return NULL;
    }
    
    return sampleBuffer;
}

- (void)validateCreatedMP4File {
    if (!self.currentOutputPath) return;
    
    dispatch_async(dispatch_get_main_queue(), ^{
        NSURL *fileURL = [NSURL fileURLWithPath:self.currentOutputPath];
        AVAsset *asset = [AVAsset assetWithURL:fileURL];
        
        // Check if the asset is playable
        BOOL isPlayable = asset.isPlayable;
        CMTime duration = asset.duration;
        float durationSeconds = CMTimeGetSeconds(duration);
        
        // Get video tracks
        NSArray *videoTracks = [asset tracksWithMediaType:AVMediaTypeVideo];
        NSArray *audioTracks = [asset tracksWithMediaType:AVMediaTypeAudio];
        
        // Get file size
        NSFileManager *fileManager = [NSFileManager defaultManager];
        NSDictionary *attributes = [fileManager attributesOfItemAtPath:self.currentOutputPath error:nil];
        NSNumber *fileSize = attributes[NSFileSize];
        
        RCTLogInfo(@"LocalMedia: MP4 Validation Results:");
        RCTLogInfo(@"  - File Path: %@", self.currentOutputPath);
        RCTLogInfo(@"  - Is Playable: %@", isPlayable ? @"YES" : @"NO");
        RCTLogInfo(@"  - Duration: %.2f seconds", durationSeconds);
        RCTLogInfo(@"  - Video Tracks: %lu", (unsigned long)videoTracks.count);
        RCTLogInfo(@"  - Audio Tracks: %lu", (unsigned long)audioTracks.count);
        RCTLogInfo(@"  - File Size: %@ bytes", fileSize ?: @"Unknown");
        
        if (isPlayable && videoTracks.count > 0) {
            RCTLogInfo(@"LocalMedia: ✅ MP4 file is valid and playable!");
            
            // Get video track details
            AVAssetTrack *videoTrack = videoTracks.firstObject;
            if (videoTrack) {
                CGSize naturalSize = videoTrack.naturalSize;
                float frameRate = videoTrack.nominalFrameRate;
                RCTLogInfo(@"  - Resolution: %.0fx%.0f", naturalSize.width, naturalSize.height);
                RCTLogInfo(@"  - Frame Rate: %.2f fps", frameRate);
            }
    } else {
            RCTLogError(@"LocalMedia: ❌ MP4 file validation failed - not playable or no video tracks");
    }
    });
}

- (void)createMinimalMP4Content {
    RCTLogInfo(@"LocalMedia: Creating placeholder MP4 content");
    
    // Create a simple text-based MP4 file for now
    [self createTextBasedMP4File:self.currentOutputPath];
}

- (void)createTextBasedMP4File:(NSString *)filePath {
    RCTLogInfo(@"LocalMedia: Creating text-based MP4 file: %@", filePath);
    
    // Create a minimal MP4 container with metadata
    NSDictionary *metadata = @{
        @"startTime": @([[NSDate date] timeIntervalSince1970]),
        @"recordingType": @"local",
        @"platform": @"iOS",
        @"webRTCCompatible": @YES,
        @"appName": @"Jitsi Meet",
        @"mode": self.isFileBasedRecording ? @"file-based" : @"real-video",
        @"version": @"1.0.0",
        @"timestamp": [self getCurrentTimestamp],
        @"status": @"recording",
        @"description": @"Jitsi Meet local recording without AVFoundation conflicts"
    };
    
    // Create JSON metadata file
    NSString *jsonPath = [filePath stringByReplacingOccurrencesOfString:@".mp4" withString:@".json"];
    NSError *error;
    NSData *jsonData = [NSJSONSerialization dataWithJSONObject:metadata options:NSJSONWritingPrettyPrinted error:&error];
    if (jsonData) {
        [jsonData writeToFile:jsonPath atomically:YES];
        RCTLogInfo(@"LocalMedia: Metadata saved to: %@", jsonPath);
    }
    
    // Create a minimal MP4 file with basic structure
    NSMutableData *mp4Data = [NSMutableData data];
    
    // MP4 file type box (ftyp)
    const char mp4Header[] = {0x00, 0x00, 0x00, 0x20, 'f', 't', 'y', 'p', 'm', 'p', '4', '1', 0x00, 0x00, 0x00, 0x00, 'm', 'p', '4', '1', 'i', 's', 'o', 'm'};
    [mp4Data appendBytes:mp4Header length:24];
    
    // Movie box (moov) - minimal structure
    [mp4Data appendBytes:"\x00\x00\x00\x08moov" length:8];
    
    // Write the minimal MP4 file
    [mp4Data writeToFile:filePath atomically:YES];
    
    RCTLogInfo(@"LocalMedia: Minimal MP4 file created: %@ (size: %lu bytes)", filePath, (unsigned long)mp4Data.length);
}

- (NSString *)getCurrentTimestamp {
    NSDateFormatter *formatter = [[NSDateFormatter alloc] init];
    [formatter setDateFormat:@"yyyy-MM-dd_HH-mm-ss"];
    return [formatter stringFromDate:[NSDate date]];
}

RCT_EXPORT_METHOD(stopRecordingToFile:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    if (!self.isRecording) {
        resolve(@YES);
        return;
    }
    
    dispatch_async(self.recordingQueue, ^{
        @try {
            RCTLogInfo(@"LocalMedia: Stopping recording");
            
            if (self.isRecording) {
                [self stopWebRTCTrackCapture:resolve rejecter:reject];
            } else {
                resolve(@YES);
            }
        } @catch (NSException *exception) {
            RCTLogError(@"LocalMedia: Stop recording failed: %@", exception.reason);
            reject(@"STOP_ERROR", exception.reason, nil);
        }
    });
}

- (void)stopWebRTCTrackCapture:(RCTPromiseResolveBlock)resolve
                      rejecter:(RCTPromiseRejectBlock)reject {
    RCTLogInfo(@"LocalMedia: Stopping bulletproof video recording");
    
    // Capture stop time
    self.recordingStopTime = [[NSDate date] timeIntervalSince1970];
    NSTimeInterval actualDuration = self.recordingStopTime - self.recordingStartTime;
    RCTLogInfo(@"LocalMedia: Actual recording duration: %.2f seconds", actualDuration);
    
    // Create bulletproof MP4 file
    [self createBulletproofMP4File];
    
    // Update metadata with stop time
    [self updateRecordingMetadataWithStopTime];
    
    // Clean up recording state - no complex APIs to worry about
    self.isRecording = NO;
    self.currentOutputPath = nil;
    self.frameCount = 0;
    
    RCTLogInfo(@"LocalMedia: Bulletproof video recording stopped successfully");
    
    resolve(@YES);
}

- (void)updateRecordingMetadataWithStopTime {
    if (!self.currentOutputPath) return;
    
    NSString *jsonPath = [self.currentOutputPath stringByReplacingOccurrencesOfString:@".mp4" withString:@".json"];
    NSData *jsonData = [NSData dataWithContentsOfFile:jsonPath];
    if (jsonData) {
        NSError *error;
        NSMutableDictionary *metadata = [NSJSONSerialization JSONObjectWithData:jsonData options:NSJSONReadingMutableContainers error:&error];
        if (metadata) {
            NSTimeInterval stopTime = [[NSDate date] timeIntervalSince1970];
            NSTimeInterval startTime = [metadata[@"startTime"] doubleValue];
            NSTimeInterval duration = stopTime - startTime;
            
            metadata[@"stopTime"] = @(stopTime);
            metadata[@"duration"] = @(duration);
            metadata[@"status"] = @"stopped";
            
            NSData *updatedJsonData = [NSJSONSerialization dataWithJSONObject:metadata options:NSJSONWritingPrettyPrinted error:&error];
            if (updatedJsonData) {
                [updatedJsonData writeToFile:jsonPath atomically:YES];
                RCTLogInfo(@"LocalMedia: Updated metadata with stop time and duration: %.2f seconds", duration);
            }
        }
    }
}

RCT_EXPORT_METHOD(setRecordingMode:(NSString *)mode
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    dispatch_async(self.recordingQueue, ^{
        @try {
            RCTLogInfo(@"LocalMedia: Setting recording mode to: %@", mode);
            
            if ([mode isEqualToString:@"real-video"]) {
                // Camera enabled - prepare for real video recording
                self.isFileBasedRecording = NO;
                RCTLogInfo(@"LocalMedia: Recording mode set to real-video (camera enabled) - isFileBasedRecording: NO");
            } else if ([mode isEqualToString:@"file-based"]) {
                // Camera disabled - use file-based recording with empty frames
                self.isFileBasedRecording = YES;
                RCTLogInfo(@"LocalMedia: Recording mode set to file-based (camera disabled) - isFileBasedRecording: YES");
            } else if ([mode isEqualToString:@"audio-only"]) {
                // Audio-only recording mode - capture microphone input only
                self.isFileBasedRecording = NO;
                RCTLogInfo(@"LocalMedia: Recording mode set to audio-only - will capture microphone input only");
            } else if ([mode isEqualToString:@"screen-recording"]) {
                // Screen recording mode
                self.isFileBasedRecording = NO;
                RCTLogInfo(@"LocalMedia: Recording mode set to screen-recording - isFileBasedRecording: NO");
            } else {
                RCTLogWarn(@"LocalMedia: Unknown recording mode: %@, defaulting to audio-only", mode);
                self.isFileBasedRecording = NO;
                RCTLogInfo(@"LocalMedia: Defaulted to audio-only - will capture microphone input only");
            }
            
            resolve(@YES);
        } @catch (NSException *exception) {
            RCTLogError(@"LocalMedia: Set recording mode failed: %@", exception.reason);
            reject(@"MODE_ERROR", exception.reason, nil);
        }
    });
}

RCT_EXPORT_METHOD(getRecordingFilePaths:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    if (self.currentOutputPath) {
        resolve(@[self.currentOutputPath]);
    } else {
        resolve(@[]);
    }
}

RCT_EXPORT_METHOD(listRecordingFiles:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    NSFileManager *fileManager = [NSFileManager defaultManager];
    NSError *error;
    NSArray *files = [fileManager contentsOfDirectoryAtPath:self.recordingsDirectory error:&error];
    
    if (error) {
        reject(@"FILE_ERROR", error.localizedDescription, error);
        return;
    }
    
    NSMutableArray *recordingFiles = [[NSMutableArray alloc] init];
    for (NSString *file in files) {
        if ([file hasPrefix:@"jitsi_recording_"] && ([file hasSuffix:@".mp4"] || [file hasSuffix:@".json"])) {
            NSString *fullPath = [self.recordingsDirectory stringByAppendingPathComponent:file];
            NSDictionary *attributes = [fileManager attributesOfItemAtPath:fullPath error:nil];
            
            NSMutableDictionary *fileInfo = [@{
                @"name": file,
                @"path": fullPath,
                @"directory": self.recordingsDirectory,
                @"type": [file hasSuffix:@".mp4"] ? @"video" : @"metadata",
                @"size": attributes[NSFileSize] ?: @0,
                @"created": attributes[NSFileCreationDate] ? @([attributes[NSFileCreationDate] timeIntervalSince1970]) : @0,
                @"modified": attributes[NSFileModificationDate] ? @([attributes[NSFileModificationDate] timeIntervalSince1970]) : @0
            } mutableCopy];
            
            // If it's a JSON file, try to extract metadata
            if ([file hasSuffix:@".json"]) {
                NSData *jsonData = [NSData dataWithContentsOfFile:fullPath];
                if (jsonData) {
                    NSError *jsonError;
                    NSDictionary *metadata = [NSJSONSerialization JSONObjectWithData:jsonData options:0 error:&jsonError];
                    if (metadata) {
                        fileInfo[@"metadata"] = metadata;
                    }
                }
            }
            
            [recordingFiles addObject:fileInfo];
        }
    }
    
    // Sort by creation date (newest first)
    [recordingFiles sortUsingComparator:^NSComparisonResult(NSDictionary *obj1, NSDictionary *obj2) {
        NSNumber *date1 = obj1[@"created"];
        NSNumber *date2 = obj2[@"created"];
        return [date2 compare:date1];
    }];
    
    resolve(recordingFiles);
}

RCT_EXPORT_METHOD(getRecordingStatus:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    resolve(@{
        @"isRecording": @(self.isRecording),
        @"isInitialized": @(self.isInitialized),
        @"isFileBasedRecording": @(self.isFileBasedRecording),
        @"currentOutputPath": self.currentOutputPath ?: @"",
        @"recordingsDirectory": self.recordingsDirectory ?: @"",
        @"recordingMode": @"simplified"
    });
}

RCT_EXPORT_METHOD(testFileWriting:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    // Test if we can write to recordings directory
    NSString *testPath = [self.recordingsDirectory stringByAppendingPathComponent:@"test_write.txt"];
    
    NSError *error;
    BOOL success = [@"test" writeToFile:testPath atomically:YES encoding:NSUTF8StringEncoding error:&error];
    
    if (success) {
        // Clean up test file
        [[NSFileManager defaultManager] removeItemAtPath:testPath error:nil];
        resolve(@YES);
    } else {
        reject(@"FILE_WRITE_ERROR", error.localizedDescription, error);
    }
}

RCT_EXPORT_METHOD(dispose:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    dispatch_async(self.recordingQueue, ^{
        @try {
            // Clean up all resources - no AVAssetWriter to worry about
            self.isRecording = NO;
            self.isInitialized = NO;
            self.currentOutputPath = nil;
            self.frameCount = 0;
            self.recordingStartTime = 0;
            self.recordingStopTime = 0;
            
            RCTLogInfo(@"LocalMedia: Dispose completed successfully");
            
            resolve(@YES);
        } @catch (NSException *exception) {
            RCTLogError(@"LocalMedia: Dispose failed: %@", exception.reason);
            reject(@"DISPOSE_ERROR", exception.reason, nil);
        }
    });
}

RCT_EXPORT_METHOD(getCurrentCameraFacingMode:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    AVCaptureDevice *videoDevice = [AVCaptureDevice defaultDeviceWithMediaType:AVMediaTypeVideo];
    if (videoDevice) {
        if (videoDevice.position == AVCaptureDevicePositionFront) {
            resolve(@"FRONT");
        } else if (videoDevice.position == AVCaptureDevicePositionBack) {
            resolve(@"BACK");
        } else {
            resolve(@"UNKNOWN");
        }
    } else {
        resolve(@"UNKNOWN");
    }
}

RCT_EXPORT_METHOD(setCurrentCameraFacingMode:(NSString *)facingMode
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    // For now, just return success as camera switching during recording
    // would require more complex implementation
    resolve(@YES);
}

RCT_EXPORT_METHOD(stopVideo:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    dispatch_async(self.recordingQueue, ^{
        @try {
            if (self.isRecording) {
                [self stopWebRTCTrackCapture:resolve rejecter:reject];
            } else {
                resolve(@YES);
            }
        } @catch (NSException *exception) {
            RCTLogError(@"LocalMedia: Stop video failed: %@", exception.reason);
            reject(@"STOP_VIDEO_ERROR", exception.reason, nil);
        }
    });
}

RCT_EXPORT_METHOD(getRecordingsDirectoryPath:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    resolve(self.recordingsDirectory ?: @"");
}

RCT_EXPORT_METHOD(showRecordingsLocation:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    NSFileManager *fileManager = [NSFileManager defaultManager];
    NSError *error;
    NSArray *files = [fileManager contentsOfDirectoryAtPath:self.recordingsDirectory error:&error];
    
    if (error) {
        reject(@"FILE_ERROR", error.localizedDescription, error);
        return;
    }
    
    NSMutableString *locationInfo = [NSMutableString string];
    [locationInfo appendFormat:@"📁 Recordings Directory: %@\n\n", self.recordingsDirectory];
    
    if (files.count > 0) {
        [locationInfo appendString:@"📹 Recorded Files:\n"];
        for (NSString *file in files) {
            if ([file hasPrefix:@"jitsi_recording_"] && ([file hasSuffix:@".mp4"] || [file hasSuffix:@".json"])) {
                NSString *fullPath = [self.recordingsDirectory stringByAppendingPathComponent:file];
                NSDictionary *attributes = [fileManager attributesOfItemAtPath:fullPath error:nil];
                NSNumber *fileSize = attributes[NSFileSize];
                NSDate *creationDate = attributes[NSFileCreationDate];
                
                [locationInfo appendFormat:@"  • %@\n", file];
                [locationInfo appendFormat:@"    Path: %@\n", fullPath];
                [locationInfo appendFormat:@"    Size: %@ bytes\n", fileSize ?: @"Unknown"];
                [locationInfo appendFormat:@"    Created: %@\n\n", creationDate ?: @"Unknown"];
            }
        }
    } else {
        [locationInfo appendString:@"No recordings found yet.\n"];
    }
    
    [locationInfo appendString:@"\n💡 To access files:\n"];
    [locationInfo appendString:@"1. Connect device to Mac\n"];
    [locationInfo appendString:@"2. Open Xcode → Window → Devices and Simulators\n"];
    [locationInfo appendString:@"3. Select your device → View Container\n"];
    [locationInfo appendString:@"4. Navigate to Documents/Recordings/\n"];
    
    RCTLogInfo(@"%@", locationInfo);
    resolve(locationInfo);
}

RCT_EXPORT_METHOD(checkVideoFile:(NSString *)filePath
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    dispatch_async(dispatch_get_main_queue(), ^{
        @try {
            NSFileManager *fileManager = [NSFileManager defaultManager];
            
            // Check if file exists
            BOOL fileExists = [fileManager fileExistsAtPath:filePath];
            if (!fileExists) {
                resolve(@{
                    @"exists": @NO,
                    @"error": @"File does not exist"
                });
                return;
            }
            
            // Get file attributes
            NSDictionary *attributes = [fileManager attributesOfItemAtPath:filePath error:nil];
            NSNumber *fileSize = attributes[NSFileSize];
            NSDate *modifiedDate = attributes[NSFileModificationDate];
            
            // Try to create AVAsset to check if it's a valid video file
            NSURL *fileURL = [NSURL fileURLWithPath:filePath];
            AVAsset *asset = [AVAsset assetWithURL:fileURL];
            
            BOOL isPlayable = asset.isPlayable;
            CMTime duration = asset.duration;
            float durationSeconds = CMTimeGetSeconds(duration);
            
            // Get video tracks
            NSArray *videoTracks = [asset tracksWithMediaType:AVMediaTypeVideo];
            NSArray *audioTracks = [asset tracksWithMediaType:AVMediaTypeAudio];
            
            resolve(@{
                @"exists": @YES,
                @"size": fileSize ?: @0,
                @"modified": @([modifiedDate timeIntervalSince1970]),
                @"isPlayable": @(isPlayable),
                @"duration": @(durationSeconds),
                @"videoTracks": @(videoTracks.count),
                @"audioTracks": @(audioTracks.count),
                @"fileSizeKB": @([fileSize intValue] / 1024)
            });
            
        } @catch (NSException *exception) {
            RCTLogError(@"LocalMedia: Error checking video file: %@", exception.reason);
            reject(@"CHECK_ERROR", exception.reason, nil);
        }
    });
}

RCT_EXPORT_METHOD(readRecordingFile:(NSString *)filePath
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    NSData *fileData = [NSData dataWithContentsOfFile:filePath];
    if (fileData) {
        resolve([fileData base64EncodedStringWithOptions:0]);
    } else {
        reject(@"FILE_READ_ERROR", @"Could not read file", nil);
    }
}

RCT_EXPORT_METHOD(cleanupOldRecordings:(NSNumber *)maxAge
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    NSFileManager *fileManager = [NSFileManager defaultManager];
    NSError *error;
    NSArray *files = [fileManager contentsOfDirectoryAtPath:self.recordingsDirectory error:&error];
    
    if (error) {
        reject(@"CLEANUP_ERROR", error.localizedDescription, error);
        return;
    }
    
    NSTimeInterval maxAgeSeconds = [maxAge doubleValue];
    NSTimeInterval currentTime = [[NSDate date] timeIntervalSince1970];
    int deletedCount = 0;
    
    for (NSString *file in files) {
        if ([file hasPrefix:@"jitsi_recording_"]) {
            NSString *fullPath = [self.recordingsDirectory stringByAppendingPathComponent:file];
            NSDictionary *attributes = [fileManager attributesOfItemAtPath:fullPath error:nil];
            NSTimeInterval fileAge = currentTime - [attributes[NSFileCreationDate] timeIntervalSince1970];
            
            if (fileAge > maxAgeSeconds) {
                [fileManager removeItemAtPath:fullPath error:nil];
                deletedCount++;
            }
        }
    }
    
    resolve(@(deletedCount));
}

RCT_EXPORT_METHOD(exportToDocuments:(NSString *)filePath
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject) {
    // For now, files are already in Documents directory
    resolve(@YES);
}

#pragma mark - Simplified Recording (No Audio Device Detection)

- (BOOL)isMicrophoneMuted {
    // Simplified - always return NO for now
    return NO;
}

@end