//
// ReplyProtocol - parsing of the phone's acknowledgement.
//
// Kept as a standalone module (no View, no Communication) so it can be unit
// tested under the simulator without a phone attached.
//
// The phone replies "ok:<detail>" or "err:<detail>". We split on the FIRST
// colon so the detail is free to contain more colons, e.g.
//   "ok:video 00:12"  ->  ok, detail "video 00:12"
//

import Toybox.Lang;

module ReplyProtocol {

    //! Everything before the first ':' (the whole string if there is none).
    function codeOf(text as String) as String {
        var idx = text.find(":");
        if (idx == null) {
            return text;
        }
        return text.substring(0, idx);
    }

    //! Everything after the first ':' ("" if there is none).
    function detailOf(text as String) as String {
        var idx = text.find(":");
        if (idx == null) {
            return "";
        }
        return text.substring(idx + 1, text.length());
    }

    //! True only for the literal "ok" code. Anything else is a failure,
    //! which is the safe default: never claim a shot happened when unsure.
    function isOk(text as String) as Boolean {
        return codeOf(text).equals("ok");
    }
}
