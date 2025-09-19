import React, { useEffect, useRef } from 'react';
import { View, Text, StyleSheet, Platform, Animated, Dimensions } from 'react-native';
import { connect } from 'react-redux';

// Create animated components
const AnimatedText = Animated.createAnimatedComponent(Text);

import { IReduxState } from '../../../../app/types';

interface IProps {
    _countdown: {
        isActive: boolean;
        count: number;
    };
}

const { width } = Dimensions.get('window');

/**
 * Simple countdown component for both Android and iOS.
 * Shows clean 10-0 countdown with minimal animations.
 */
const RecordingCountdown: React.FC<IProps> = ({ _countdown }) => {
    const scaleAnim = useRef(new Animated.Value(1)).current;
    const previousCount = useRef(10);

    // Safety check for count value
    const count = typeof _countdown?.count === 'number' ? _countdown.count : 0;
    const isActive = _countdown?.isActive || false;

    useEffect(() => {
        // Simple scale animation when count changes
        if (isActive && count !== previousCount.current && count < 10) {
            Animated.sequence([
                Animated.timing(scaleAnim, {
                    toValue: 1.2,
                    duration: 100,
                    useNativeDriver: true,
                }),
                Animated.timing(scaleAnim, {
                    toValue: 1,
                    duration: 100,
                    useNativeDriver: true,
                }),
            ]).start();

            previousCount.current = count;
        }
    }, [count, isActive, scaleAnim]);

    // Don't render if countdown is not active
    if (!isActive) {
        return null;
    }

    return (
        <View style={styles.container}>
            <View style={styles.countdownBox}>
                <AnimatedText 
                    style={[
                        styles.countdownText,
                        { transform: [{ scale: scaleAnim }] }
                    ]}
                >
                    {count}
                </AnimatedText>
            </View>
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        position: 'absolute',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        backgroundColor: 'rgba(0, 0, 0, 0.8)',
        justifyContent: 'center',
        alignItems: 'center',
        zIndex: 9999,
        elevation: 9999, // For Android
    },
    countdownBox: {
        width: width < 400 ? 100 : 120, // Smaller on small devices
        height: width < 400 ? 100 : 120,
        borderRadius: width < 400 ? 50 : 60,
        backgroundColor: '#ff4444',
        justifyContent: 'center',
        alignItems: 'center',
        borderWidth: 3,
        borderColor: '#ffffff',
        shadowColor: '#000',
        shadowOffset: {
            width: 0,
            height: 4,
        },
        shadowOpacity: 0.3,
        shadowRadius: 6,
        elevation: 8, // For Android shadow
    },
    countdownText: {
        fontSize: width < 400 ? 36 : 48, // Smaller font on small devices
        fontWeight: 'bold',
        color: '#ffffff',
        textShadowColor: 'rgba(0, 0, 0, 0.75)',
        textShadowOffset: { width: 0, height: 1 },
        textShadowRadius: 2,
    },
});

/**
 * Maps (parts of) the Redux state to the associated props for this component.
 *
 * @param {Object} state - The Redux state.
 * @private
 * @returns {IProps}
 */
function _mapStateToProps(state: IReduxState) {
    return {
        _countdown: state['features/countdown']
    };
}

export default connect(_mapStateToProps)(RecordingCountdown);
