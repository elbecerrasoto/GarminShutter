//
// Unit tests for ReplyProtocol.
//
// Run with:  scripts/run-tests.sh
// (compiled with `monkeyc -t`, executed by `monkeydo -t` in the simulator)
//

import Toybox.Lang;
import Toybox.Test;

(:test)
function testOkWithDetail(logger as Test.Logger) as Boolean {
    Test.assert(ReplyProtocol.isOk("ok:photo"));
    Test.assert(ReplyProtocol.detailOf("ok:photo").equals("photo"));
    return true;
}

(:test)
function testErrWithDetail(logger as Test.Logger) as Boolean {
    Test.assert(!ReplyProtocol.isOk("err:camera busy"));
    Test.assert(ReplyProtocol.detailOf("err:camera busy").equals("camera busy"));
    return true;
}

(:test)
function testDetailMayContainColons(logger as Test.Logger) as Boolean {
    Test.assert(ReplyProtocol.isOk("ok:video 00:12"));
    Test.assert(ReplyProtocol.detailOf("ok:video 00:12").equals("video 00:12"));
    return true;
}

(:test)
function testCodeWithoutColon(logger as Test.Logger) as Boolean {
    Test.assert(ReplyProtocol.isOk("ok"));
    Test.assert(ReplyProtocol.detailOf("ok").equals(""));
    Test.assert(ReplyProtocol.codeOf("ok").equals("ok"));
    return true;
}

(:test)
function testCaseSensitiveOk(logger as Test.Logger) as Boolean {
    // "OK" is not our protocol; treating it as success would hide a bug.
    Test.assert(!ReplyProtocol.isOk("OK:photo"));
    return true;
}

(:test)
function testEmptyStringIsFailure(logger as Test.Logger) as Boolean {
    Test.assert(!ReplyProtocol.isOk(""));
    Test.assert(ReplyProtocol.detailOf("").equals(""));
    return true;
}
