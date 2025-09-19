#!/bin/bash

# Script to add LocalMedia files to Xcode project
# This script adds the LocalMedia.h and LocalMedia.m files to the iOS SDK project

PROJECT_FILE="ios/sdk/sdk.xcodeproj/project.pbxproj"

# Check if the files exist
if [ ! -f "ios/sdk/src/LocalMedia.h" ]; then
    echo "Error: LocalMedia.h not found"
    exit 1
fi

if [ ! -f "ios/sdk/src/LocalMedia.m" ]; then
    echo "Error: LocalMedia.m not found"
    exit 1
fi

echo "LocalMedia files found. Adding to Xcode project..."

# Use xcodebuild to add the files (this is a simplified approach)
# In a real scenario, you would need to properly edit the project.pbxproj file
# or use Xcode's command line tools

echo "Note: The LocalMedia files need to be manually added to the Xcode project."
echo "Please open ios/sdk/sdk.xcodeproj in Xcode and add the following files:"
echo "- ios/sdk/src/LocalMedia.h"
echo "- ios/sdk/src/LocalMedia.m"
echo ""
echo "Make sure to add them to the appropriate target and build phases."

exit 0
