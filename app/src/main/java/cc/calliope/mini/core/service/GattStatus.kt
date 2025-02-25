package cc.calliope.mini.core.service

enum class GattStatus(val code: Int, val description: String) {
    GATT_CONNECTION_CONGESTED(143, "Connection Congested"),
    GATT_CONNECTION_TIMEOUT(147, "Connection Timeout"),
    GATT_FAILURE(257, "Failure"),
    GATT_INSUFFICIENT_AUTHENTICATION(5, "Insufficient Authentication"),
    GATT_INSUFFICIENT_AUTHORIZATION(8, "Insufficient Authorization"),
    GATT_INSUFFICIENT_ENCRYPTION(15, "Insufficient Encryption"),
    GATT_INVALID_ATTRIBUTE_LENGTH(13, "Invalid Attribute Length"),
    GATT_INVALID_OFFSET(7, "Invalid Offset"),
    GATT_READ_NOT_PERMITTED(2, "Read Not Permitted"),
    GATT_REQUEST_NOT_SUPPORTED(6, "Request Not Supported"),
    GATT_SUCCESS(0, "Success"),
    GATT_WRITE_NOT_PERMITTED(3, "Write Not Permitted");

    companion object {
        fun fromCode(code: Int): GattStatus? {
            return entries.find { it.code == code }
        }
    }
}