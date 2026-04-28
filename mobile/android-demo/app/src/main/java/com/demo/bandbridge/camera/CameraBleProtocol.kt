package com.demo.bandbridge.camera

object CameraBleProtocol {
    const val SERVICE_UUID = "7D8A1000-4B2A-4D44-9F72-5E7BC9A10000"
    const val CONTROL_CHAR_UUID = "7D8A1001-4B2A-4D44-9F72-5E7BC9A10000"
    const val STATUS_CHAR_UUID = "7D8A1002-4B2A-4D44-9F72-5E7BC9A10000"
    const val META_CHAR_UUID = "7D8A1003-4B2A-4D44-9F72-5E7BC9A10000"
    const val CHUNK_CHAR_UUID = "7D8A1004-4B2A-4D44-9F72-5E7BC9A10000"

    const val COMMAND_PING = "PING"
    const val COMMAND_CAPTURE = "CAPTURE"
    const val COMMAND_FETCH_LAST = "FETCH_LAST"
    const val COMMAND_SET_CONFIG = "SETCFG"
    const val COMMAND_ANALYZE = "ANALYZE"
    const val COMMAND_TIMED_START = "TIMEDSTART"

    const val DEFAULT_CHUNK_SIZE = 180

    fun buildConfigCommand(frameSize: String, jpegQuality: Int): String {
        return "$COMMAND_SET_CONFIG|${frameSize.uppercase()}|$jpegQuality"
    }
}
