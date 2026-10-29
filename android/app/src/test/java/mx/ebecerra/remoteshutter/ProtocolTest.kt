package mx.ebecerra.remoteshutter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract between the watch app and the phone app.
 *
 * Every test here maps to something that would break silently on a tripod
 * 20 metres away: a shot that reports FAILED, a video that never starts, an
 * acknowledgement the watch cannot parse.
 */
class ProtocolTest {

    // --- inbound: watch -> phone -------------------------------------------

    @Test
    fun parsesPhotoCommand() {
        assertEquals(Protocol.Command.Photo, Protocol.parseCommand("photo"))
    }

    @Test
    fun parsesVideoCommand() {
        assertEquals(Protocol.Command.Video, Protocol.parseCommand("video"))
    }

    @Test
    fun toleratesCaseAndSurroundingWhitespace() {
        assertEquals(Protocol.Command.Photo, Protocol.parseCommand("  PHOTO \n"))
        assertEquals(Protocol.Command.Video, Protocol.parseCommand("\tVideo  "))
    }

    @Test
    fun rejectsUnknownCommand() {
        val cmd = Protocol.parseCommand("selfie")
        assertTrue(cmd is Protocol.Command.Unknown)
        assertEquals("selfie", (cmd as Protocol.Command.Unknown).raw)
    }

    @Test
    fun rejectsNullAndEmpty() {
        assertTrue(Protocol.parseCommand(null) is Protocol.Command.Unknown)
        assertTrue(Protocol.parseCommand("   ") is Protocol.Command.Unknown)
    }

    // --- the SDK always wraps the payload in a List ------------------------

    @Test
    fun unwrapsSingleElementList() {
        assertEquals(Protocol.Command.Photo, Protocol.parseMessage(listOf("photo")))
    }

    @Test
    fun unwrapsListWithNoise() {
        assertEquals(Protocol.Command.Video, Protocol.parseMessage(listOf(" video ")))
    }

    @Test
    fun emptyListIsUnknown() {
        assertTrue(Protocol.parseMessage(emptyList<String>()) is Protocol.Command.Unknown)
    }

    @Test
    fun nullListIsUnknown() {
        assertTrue(Protocol.parseMessage(null) is Protocol.Command.Unknown)
    }

    @Test
    fun nonStringElementBecomesText() {
        val cmd = Protocol.parseMessage(listOf(42))
        assertTrue(cmd is Protocol.Command.Unknown)
        assertEquals("42", (cmd as Protocol.Command.Unknown).raw)
    }

    @Test
    fun byteArrayElementIsDecoded() {
        assertEquals(Protocol.Command.Photo, Protocol.parseMessage(listOf("photo".toByteArray())))
    }

    // --- outbound: phone -> watch ------------------------------------------

    @Test
    fun okRoundTrips() {
        assertEquals(Protocol.Reply.Ok("photo"), Protocol.parseReply(Protocol.ok("photo")))
    }

    @Test
    fun errRoundTrips() {
        assertEquals(Protocol.Reply.Err("camera busy"), Protocol.parseReply(Protocol.err("camera busy")))
    }

    @Test
    fun detailMayContainColons() {
        // Splitting on the first colon only is what makes this survive.
        assertEquals(Protocol.Reply.Ok("video 00:12"), Protocol.parseReply("ok:video 00:12"))
    }

    @Test
    fun codeWithoutDetailIsOk() {
        assertEquals(Protocol.Reply.Ok(""), Protocol.parseReply("ok"))
    }

    @Test
    fun garbageIsAnError() {
        assertEquals(Protocol.Reply.Err(""), Protocol.parseReply("wat"))
        assertEquals(Protocol.Reply.Err(""), Protocol.parseReply(null))
    }

    @Test
    fun emptyDetailIsStillValid() {
        assertEquals(Protocol.Reply.Ok(""), Protocol.parseReply(Protocol.ok()))
        assertEquals(Protocol.Reply.Err(""), Protocol.parseReply(Protocol.err()))
    }

    /**
     * The one test that matters most: everything the phone can emit must be
     * classifiable by the same rules the watch uses. If this fails, the watch
     * shows "FAILED" for a shot that actually succeeded.
     */
    @Test
    fun everyCameraResultIsClassifiable() {
        val results = listOf(
            Protocol.ok("photo"),
            Protocol.ok("recording"),
            Protocol.ok("video 00:12"),
            Protocol.err("camera busy"),
            Protocol.err("camera init failed"),
            Protocol.err("no camera"),
        )
        for (r in results) {
            val parsed = Protocol.parseReply(r)
            if (r.startsWith(Protocol.OK)) {
                assertTrue("expected ok for $r", parsed is Protocol.Reply.Ok)
            } else {
                assertTrue("expected err for $r", parsed is Protocol.Reply.Err)
            }
        }
    }
}
