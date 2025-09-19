import { START_COUNTDOWN, STOP_COUNTDOWN, UPDATE_COUNTDOWN } from './actionTypes';

/**
 * Starts the countdown.
 *
 * @param {Function} onComplete - Callback to execute when countdown completes.
 * @returns {Object}
 */
export function startCountdown(onComplete: () => void) {
    return {
        type: START_COUNTDOWN,
        onComplete
    };
}

/**
 * Stops the countdown.
 *
 * @returns {Object}
 */
export function stopCountdown() {
    return {
        type: STOP_COUNTDOWN
    };
}
