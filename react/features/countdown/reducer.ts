import ReducerRegistry from '../base/redux/ReducerRegistry';

import { START_COUNTDOWN, STOP_COUNTDOWN, UPDATE_COUNTDOWN } from './actionTypes';

export interface ICountdownState {
    isActive: boolean;
    count: number;
    onComplete?: () => void;
}

const DEFAULT_STATE: ICountdownState = {
    isActive: false,
    count: 10
};

/**
 * Reduces redux actions which affect the countdown state.
 *
 * @param {Object} state - The current redux state.
 * @param {Object} action - The redux action to reduce.
 * @returns {Object} The next redux state which is the result of reducing the
 * specified {@code action}.
 */
ReducerRegistry.register<ICountdownState>('features/countdown',
    (state = DEFAULT_STATE, action: any): ICountdownState => {
        switch (action.type) {
        case START_COUNTDOWN:
            return {
                ...state,
                isActive: true,
                count: 10,
                onComplete: action.onComplete
            };

        case UPDATE_COUNTDOWN:
            return {
                ...state,
                count: action.count
            };

        case STOP_COUNTDOWN:
            return {
                ...state,
                isActive: false,
                count: 10,
                onComplete: undefined
            };
        }

        return state;
    });
