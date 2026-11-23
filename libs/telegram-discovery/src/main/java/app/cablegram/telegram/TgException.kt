package app.cablegram.telegram

class TgException(val code: Int, val name: String) : Exception("$code $name") {
    /** Telegram asks to wait before retrying (`FLOOD_WAIT_x` or "Too Many Requests: retry after x"). */
    val retryAfterSeconds: Int?
        get() = Regex("""(?:FLOOD_WAIT_|retry after )(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
}
