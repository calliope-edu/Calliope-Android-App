package cc.calliope.mini.core.bluetooth

import androidx.annotation.StringRes
import cc.calliope.mini.R

/**
 * GATT / HCI status codes as delivered to [android.bluetooth.BluetoothGattCallback],
 * each with a user-facing message. Replaces the two former enums (one with
 * English descriptions for logs, one with string resources for the UI).
 */
enum class GattStatus(val code: Int, @StringRes val messageRes: Int) {
    SUCCESS(0x00, R.string.gatt_success),
    INVALID_HANDLE(0x01, R.string.gatt_invalid_handle),
    READ_NOT_PERMITTED(0x02, R.string.gatt_read_not_permit),
    WRITE_NOT_PERMITTED(0x03, R.string.gatt_write_not_permitted),
    INVALID_PDU(0x04, R.string.gatt_invalid_pdu),
    INSUFFICIENT_AUTHENTICATION(0x05, R.string.gatt_insuf_authentication),
    REQUEST_NOT_SUPPORTED(0x06, R.string.gatt_request_not_supported),
    INVALID_OFFSET(0x07, R.string.gatt_invalid_offset),
    /** Also the HCI "connection timeout" reason on a link drop. */
    INSUFFICIENT_AUTHORIZATION(0x08, R.string.gatt_insufficient_authorization),
    PREPARE_QUEUE_FULL(0x09, R.string.gatt_prepare_q_full),
    NOT_FOUND(0x0A, R.string.gatt_not_found),
    NOT_LONG(0x0B, R.string.gatt_not_long),
    INSUFFICIENT_KEY_SIZE(0x0C, R.string.gatt_insuf_key_size),
    INVALID_ATTRIBUTE_LENGTH(0x0D, R.string.gatt_invalid_attribute_length),
    UNLIKELY_ERROR(0x0E, R.string.gatt_err_unlikely),
    INSUFFICIENT_ENCRYPTION(0x0F, R.string.gatt_insufficient_encryption),
    UNSUPPORTED_GROUP_TYPE(0x10, R.string.gatt_unsupport_grp_type),
    INSUFFICIENT_RESOURCES(0x11, R.string.gatt_insuf_resource),
    /** HCI "remote user terminated connection" — the board dropped the link. */
    DISCONNECTED_BY_DEVICE(0x13, R.string.gatt_disconnected_by_device),
    NO_BONDED(0x16, R.string.gatt_no_bonded),
    NO_RESOURCES(0x80, R.string.gatt_no_ressources),
    INTERNAL_ERROR(0x81, R.string.gatt_internal_error),
    WRONG_STATE(0x82, R.string.gatt_wrong_state),
    DB_FULL(0x83, R.string.gatt_db_full),
    BUSY(0x84, R.string.gatt_busy),
    /** The stack's generic 133 — usually a transient connect failure. */
    ERROR(0x85, R.string.gatt_error),
    CMD_STARTED(0x86, R.string.gatt_cmd_started),
    ILLEGAL_PARAMETER(0x87, R.string.gatt_illegal_parameter),
    PENDING(0x88, R.string.gatt_pending),
    AUTH_FAIL(0x89, R.string.gatt_auth_fail),
    MORE(0x8A, R.string.gatt_more),
    INVALID_CFG(0x8B, R.string.gatt_invalid_cfg),
    SERVICE_STARTED(0x8C, R.string.gatt_service_started),
    ENCRYPTED_NO_MITM(0x8D, R.string.gatt_encryped_no_mitm),
    NOT_ENCRYPTED(0x8E, R.string.gatt_not_encrypted),
    CONNECTION_CONGESTED(0x8F, R.string.gatt_connection_congested),
    FAILURE(0x101, R.string.gatt_failure),
    UNIDENTIFIED(0x999, R.string.gatt_unidentified);

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** Never null: unknown codes map to [UNIDENTIFIED]. */
        @JvmStatic
        fun fromCode(code: Int): GattStatus = byCode[code] ?: UNIDENTIFIED
    }
}
