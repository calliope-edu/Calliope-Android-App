package cc.calliope.mini.core.service

enum class ConnectionState(val code: Int, val description: String) {
    STATE_CONNECTED(2, "Connected"),
    STATE_CONNECTING(1, "Connecting"),
    STATE_DISCONNECTED(0, "Disconnected"),
    STATE_DISCONNECTING(3, "Disconnecting");

    companion object {
        fun fromCode(code: Int): ConnectionState? {
            return entries.find { it.code == code }
        }
    }
}