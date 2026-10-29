//
// Remote Shutter — Connect IQ watch app.
//
// The watch is a *dumb remote*: it sends a one-word command to the phone
// app and displays whatever the phone replies. All camera logic lives on
// the phone, so the watch side stays tiny and cannot break.
//
// Wire protocol (plain strings, no serialization to get wrong):
//   watch -> phone : "photo" | "video"
//   phone -> watch : "ok:<detail>" | "err:<detail>"
//

import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

class ShutterApp extends Application.AppBase {

    private var _view as ShutterView;
    private var _phoneMethod as Method(msg as PhoneAppMessage) as Void;

    public function initialize() {
        AppBase.initialize();

        // Receive replies from the companion app. registerForPhoneAppMessages
        // replaced the deprecated setMailboxListener in Connect IQ 4.0.0, but
        // `has` keeps this compiling for older devices too.
        _phoneMethod = method(:onPhoneMessage);
        if (Communications has :registerForPhoneAppMessages) {
            Communications.registerForPhoneAppMessages(_phoneMethod);
        }

        _view = new ShutterView();
    }

    public function getInitialView() as [Views] or [Views, InputDelegates] {
        return [_view, new ShutterDelegate(_view)] as [Views, InputDelegates];
    }

    public function onStart(state as Dictionary?) as Void {
    }

    public function onStop(state as Dictionary?) as Void {
    }

    //! Called when the phone app sends a message back.
    public function onPhoneMessage(msg as PhoneAppMessage) as Void {
        _view.onPhoneReply(msg.data);
        WatchUi.requestUpdate();
    }
}
