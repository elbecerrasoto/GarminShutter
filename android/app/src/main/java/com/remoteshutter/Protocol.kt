package com.remoteshutter

/**
 * The wire protocol, isolated from Android so it can be unit-tested on a
 * plain JVM.
 *
 *     watch -> phone : "photo" | "video"
 *     phone -> watch : "ok:<detail>" | "err:<detail>"
 *
 * Rules the tests pin down (each one is a bug that was actually hit or that
 * the platform makes easy to hit):
 *
 *  - Commands are matched case-insensitively and whitespace-tolerantly: the
 *    payload crosses BLE, a binder boundary and a Java deserializer before
 *    we see it.
 *  - The Connect IQ SDK hands us a List, never a bare object. Tolerating a
 *    bare String is what stops every shot from being reported as FAILED.
 *  - Replies split on the FIRST colon only, so the detail may contain ':'
 *    (e.g. "ok:video 00:12").
 *  - Anything unrecognised is reported as unknown, never guessed at.
 */
object Protocol {

    const val CMD_PHOTO = "photo"
    const val CMD_VIDEO = "video"

    const val OK = "ok"
    const val ERR = "err"

    sealed interface Command {
        object Photo : Command
        object Video : Command
        data class Unknown(val raw: String?) : Command
    }

    sealed interface Reply {
        data class Ok(val detail: String) : Reply
        data class Err(val detail: String) : Reply
    }

    fun parseCommand(raw: String?): Command {
        val text = raw?.trim()?.lowercase() ?: return Command.Unknown(null)
        return when (text) {
            CMD_PHOTO -> Command.Photo
            CMD_VIDEO -> Command.Video
            else -> Command.Unknown(text)
        }
    }

    /** Unwrap what the Connect IQ SDK delivers and interpret it. */
    fun parseMessage(message: List<Any?>?): Command {
        val first = message?.firstOrNull() ?: return Command.Unknown(null)
        val text = when (first) {
            is String -> first
            is ByteArray -> String(first)
            else -> first.toString()
        }
        return parseCommand(text)
    }

    fun ok(detail: String = ""): String = "$OK:$detail"
    fun err(detail: String = ""): String = "$ERR:$detail"

    /** Mirror of the watch-side parser (ReplyProtocol.mc), used to prove the
     *  two halves agree on the same strings. */
    fun parseReply(raw: String?): Reply {
        val text = raw ?: return Reply.Err("")
        val idx = text.indexOf(':')
        val code = if (idx < 0) text else text.substring(0, idx)
        val detail = if (idx < 0) "" else text.substring(idx + 1)
        return if (code.equals(OK, ignoreCase = true)) Reply.Ok(detail) else Reply.Err(detail)
    }
}
