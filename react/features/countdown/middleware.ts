import MiddlewareRegistry from '../base/redux/MiddlewareRegistry';
import { IStore } from '../app/types';

import { START_COUNTDOWN, STOP_COUNTDOWN, UPDATE_COUNTDOWN } from './actionTypes';

// Store active timers to prevent memory leaks
let activeTimer: any = null;

/**
 * Middleware to handle countdown logic.
 */
MiddlewareRegistry.register(({ dispatch, getState }: { dispatch: IStore['dispatch']; getState: IStore['getState'] }) => (next: any) => (action: any) => {
    const result = next(action);

    switch (action.type) {
    case START_COUNTDOWN: {
        // Clear any existing timer to prevent multiple countdowns
        if (activeTimer) {
            clearInterval(activeTimer);
            activeTimer = null;
        }

        const { onComplete } = action;
        let count = 10;

        activeTimer = setInterval(() => {
            try {
                const state = getState();
                const countdown = state['features/countdown'];

                if (!countdown || !countdown.isActive) {
                    clearInterval(activeTimer);
                    activeTimer = null;
                    return;
                }

                // Use the current count from state, or fallback to local count
                count = countdown.count || count;
                count--;
                dispatch({
                    type: UPDATE_COUNTDOWN,
                    count
                });

                if (count <= 0) {
                    clearInterval(activeTimer);
                    activeTimer = null;
                    // Stop countdown first
                    dispatch({
                        type: STOP_COUNTDOWN
                    });
                    // Small delay to ensure UI cleanup before calling onComplete
                    setTimeout(() => {
                        if (onComplete) {
                            try {
                                onComplete();
                            } catch (error) {
                                console.warn('Countdown onComplete callback error:', error);
                            }
                        }
                    }, 50);
                }
            } catch (error) {
                console.warn('Countdown timer error:', error);
                clearInterval(activeTimer);
                activeTimer = null;
            }
        }, 1000);

        break;
    }
    case STOP_COUNTDOWN: {
        // Clear timer when countdown is stopped
        if (activeTimer) {
            clearInterval(activeTimer);
            activeTimer = null;
        }
        break;
    }
    }

    return result;
});
