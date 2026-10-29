//
// Remote Shutter - input handling.
//
// We use BehaviorDelegate rather than raw key codes: the firmware maps the
// device's primary button (GPS/START on an Instinct) to onSelect(), so the
// same source works on round, semi-octagon and rectangular watches.
//

import Toybox.Lang;
import Toybox.WatchUi;

class ShutterDelegate extends WatchUi.BehaviorDelegate {

    private var _view as ShutterView;

    public function initialize(view as ShutterView) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    // Primary button: take a photo / toggle recording.
    public function onSelect() as Boolean {
        _view.shoot();
        return true;
    }

    // UP.
    public function onPreviousPage() as Boolean {
        _view.setMode($.MODE_PHOTO);
        return true;
    }

    // DOWN.
    public function onNextPage() as Boolean {
        _view.setMode($.MODE_VIDEO);
        return true;
    }

    // BACK is intentionally left to the default behaviour (exit the app).
}
