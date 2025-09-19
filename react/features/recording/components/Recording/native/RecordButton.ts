import { Platform } from 'react-native';
import { connect } from 'react-redux';

import { IReduxState } from '../../../../app/types';
import { openDialog } from '../../../../base/dialog/actions';
import { IOS_RECORDING_ENABLED, RECORDING_ENABLED } from '../../../../base/flags/constants';
import { getFeatureFlag } from '../../../../base/flags/functions';
import { translate } from '../../../../base/i18n/functions';
import { navigate }
    from '../../../../mobile/navigation/components/conference/ConferenceNavigationContainerRef';
import { screen } from '../../../../mobile/navigation/routes';
import {
    IProps, _mapStateToProps as abstractStartLiveStreamDialogMapStateToProps
} from '../../LiveStream/AbstractStartLiveStreamDialog';
import AbstractRecordButton, {
    IProps as AbstractProps,
    _mapStateToProps as _abstractMapStateToProps
} from '../AbstractRecordButton';

import StopRecordingDialog from './StopRecordingDialog';

type Props = IProps & AbstractProps;

/**
 * Button for opening a screen where a recording session can be started.
 */
class RecordButton extends AbstractRecordButton<Props> {

    /**
     * Handles clicking / pressing the button.
     *
     * @override
     * @protected
     * @returns {void}
     */
    _onHandleClick() {
        const { _isRecordingRunning, dispatch } = this.props;

        if (_isRecordingRunning) {
            dispatch(openDialog(StopRecordingDialog));
        } else {
            navigate(screen.conference.recording);
        }
    }
}

/**
 * Maps (parts of) the redux state to the associated props for this component.
 *
 * @param {Object} state - The redux state.
 * @param {Object} ownProps - The properties explicitly passed to the component
 * instance.
 * @private
 * @returns {Props}
 */
export function mapStateToProps(state: IReduxState) {
    const enabled = getFeatureFlag(state, RECORDING_ENABLED, true);
    const iosRecordingFlag = getFeatureFlag(state, IOS_RECORDING_ENABLED, false);
    const iosEnabled = Platform.OS !== 'ios' || iosRecordingFlag;
    const abstractProps = _abstractMapStateToProps(state);

    console.log('RecordButton mapStateToProps:', {
        platform: Platform.OS,
        enabled,
        iosRecordingFlag,
        iosEnabled,
        abstractPropsVisible: abstractProps.visible,
        finalVisible: Boolean(enabled && iosEnabled && abstractProps.visible),
        featureFlags: state['features/base/flags'] || 'no flags state'
    });

    // For now, let's enable recording on iOS regardless of the feature flag
    // since we know the native implementation is working
    const shouldShowButton = Platform.OS === 'ios' ? true : Boolean(enabled && iosEnabled && abstractProps.visible);

    return {
        ...abstractProps,
        ...abstractStartLiveStreamDialogMapStateToProps(state),
        visible: shouldShowButton
    };
}

export default translate(connect(mapStateToProps)(RecordButton));
