//
// Remote Shutter — the single screen of the watch app.
//
// Layout on a 176x176 Instinct 2:
//
//    PHOTO            <- current mode (UP/DOWN switches)
//
//     READY           <- big, the only thing you read at arm's length
//    Press START      <- last detail / error text
//
//    START = SHOOT    <- static hint
//

import Toybox.Attention;
import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

enum ShutterState {
    STATE_READY,
    STATE_SENDING,
    STATE_OK,
    STATE_FAIL
}

enum ShutterMode {
    MODE_PHOTO,
    MODE_VIDEO
}

class ShutterView extends WatchUi.View {

    // Wait this long for the phone's ack before showing a failure.
    private const REPLY_TIMEOUT_MS = 6000;
    // Ignore a second press within this window (guards against key bounce
    // and against onSelect + onKey both firing on some firmwares).
    private const DEBOUNCE_MS = 800;

    private var _state as ShutterState = $.STATE_READY;
    private var _mode as ShutterMode = $.MODE_PHOTO;
    private var _detail as String = "Press START";
    private var _lastTriggerMs as Number = 0;
    private var _timeout as Timer.Timer?;

    public function initialize() {
        View.initialize();
    }

    public function onLayout(dc as Dc) as Void {
    }

    public function onShow() as Void {
        WatchUi.requestUpdate();
    }

    public function onHide() as Void {
    }

    // ---------------------------------------------------------------- state

    public function setMode(mode as ShutterMode) as Void {
        if (_mode == mode) {
            return;
        }
        _mode = mode;
        if (_state == $.STATE_READY or _state == $.STATE_OK) {
            _state = $.STATE_READY;
            _detail = (_mode == $.MODE_PHOTO) ? "Press START" : "START = rec/stop";
        }
        WatchUi.requestUpdate();
    }

    public function shoot() as Void {
        if (_state == $.STATE_SENDING) {
            return;
        }
        var now = System.getTimer();
        if (now - _lastTriggerMs < DEBOUNCE_MS) {
            return;
        }
        _lastTriggerMs = now;

        _state = $.STATE_SENDING;
        _detail = "Sending...";
        WatchUi.requestUpdate();

        var cmd = (_mode == $.MODE_PHOTO) ? "photo" : "video";
        Communications.transmit(cmd, null, new ShutterTxListener(self));
        startTimeout();
    }

    //! The BLE hand-off completed: the phone has (or will have) the message.
    //! We deliberately keep STATE_SENDING here and wait for the phone's ack,
    //! because "sent" is not the same as "the camera actually fired".
    public function onTransmitComplete() as Void {
    }

    public function onTransmitError() as Void {
        _state = $.STATE_FAIL;
        _detail = "No phone";
        stopTimeout();
        buzz(120);
        WatchUi.requestUpdate();
    }

    //! Parse "ok:photo saved" / "err:busy" coming back from the phone.
    public function onPhoneReply(data as Object?) as Void {
        if (data == null) {
            return;
        }

        // The companion normally sends a plain String, but the SDK is free to
        // hand us a one-element Array instead. Unwrap, then judge.
        if (data instanceof Lang.Array) {
            var list = data as Lang.Array<Lang.Object>;
            if (list.size() == 0) {
                return;
            }
            applyReply(list[0].toString());
            return;
        }

        applyReply(data.toString());
    }

    //! Parse "ok:photo" / "err:busy" and update the screen.
    private function applyReply(text as String) as Void {
        stopTimeout();

        // Parsing lives in ReplyProtocol so it can be unit tested.
        if (ReplyProtocol.isOk(text)) {
            _state = $.STATE_OK;
            buzz(60);
        } else {
            _state = $.STATE_FAIL;
            buzz(200);
        }
        _detail = ReplyProtocol.detailOf(text);
        WatchUi.requestUpdate();
    }

    // --------------------------------------------------------------- timers

    private function startTimeout() as Void {
        if (_timeout == null) {
            _timeout = new Timer.Timer();
        }
        _timeout.start(method(:onTimeout), REPLY_TIMEOUT_MS, false);
    }

    private function stopTimeout() as Void {
        if (_timeout != null) {
            _timeout.stop();
        }
    }

    public function onTimeout() as Void {
        if (_state == $.STATE_SENDING) {
            _state = $.STATE_FAIL;
            _detail = "No reply";
            WatchUi.requestUpdate();
        }
    }

    // --------------------------------------------------------------- output

    private function buzz(strength as Number) as Void {
        if (Attention has :vibrate) {
            var pattern = [new Attention.VibeProfile(strength, 180)] as Array<Attention.VibeProfile>;
            Attention.vibrate(pattern);
        }
    }

    private function statusText() as String {
        switch (_state) {
            case $.STATE_READY:
                return "READY";
            case $.STATE_SENDING:
                return "...";
            case $.STATE_OK:
                return "OK";
            default:
                return "FAILED";
        }
    }

    public function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);

        // Mode, top.
        dc.drawText(w / 2, 22, Graphics.FONT_TINY,
                    (_mode == $.MODE_PHOTO) ? "PHOTO" : "VIDEO",
                    Graphics.TEXT_JUSTIFY_CENTER);

        // Big status, middle.
        dc.drawText(w / 2, h / 2 - 16, Graphics.FONT_MEDIUM, statusText(),
                    Graphics.TEXT_JUSTIFY_CENTER);

        // Detail, just under the status.
        dc.drawText(w / 2, h / 2 + 18, Graphics.FONT_TINY, _detail,
                    Graphics.TEXT_JUSTIFY_CENTER);

        // Static hint, bottom.
        dc.drawText(w / 2, h - 28, Graphics.FONT_XTINY,
                    (_mode == $.MODE_PHOTO) ? "START  =  SHOOT" : "START  =  REC/STOP",
                    Graphics.TEXT_JUSTIFY_CENTER);
    }
}

//! Tells us whether the BLE hand-off to the phone worked.
class ShutterTxListener extends Communications.ConnectionListener {

    private var _view as ShutterView;

    public function initialize(view as ShutterView) {
        Communications.ConnectionListener.initialize();
        _view = view;
    }

    public function onComplete() as Void {
        _view.onTransmitComplete();
    }

    public function onError() as Void {
        _view.onTransmitError();
    }
}
