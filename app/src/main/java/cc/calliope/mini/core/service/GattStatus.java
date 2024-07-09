package cc.calliope.mini.core.service;

import java.util.HashMap;
import java.util.Map;

import cc.calliope.mini.R;

public enum GattStatus {
    GATT_SUCCESS(0x00, R.string.gatt_success),
    GATT_INVALID_HANDLE(0x01, R.string.gatt_invalid_handle),
    GATT_READ_NOT_PERMIT(0x02, R.string.gatt_read_not_permit),
    GATT_WRITE_NOT_PERMITTED(0x03, R.string.gatt_write_not_permitted),
    GATT_INVALID_PDU(0x04, R.string.gatt_invalid_pdu),
    GATT_INSUF_AUTHENTICATION(0x05, R.string.gatt_insuf_authentication),
    GATT_REQUEST_NOT_SUPPORTED(0x06, R.string.gatt_request_not_supported),
    GATT_INVALID_OFFSET(0x07, R.string.gatt_invalid_offset),
    GATT_INSUFFICIENT_AUTHORIZATION(0x08, R.string.gatt_insufficient_authorization),
    GATT_PREPARE_Q_FULL(0x09, R.string.gatt_prepare_q_full),
    GATT_NOT_FOUND(0x0A, R.string.gatt_not_found),
    GATT_NOT_LONG(0x0B, R.string.gatt_not_long),
    GATT_INSUF_KEY_SIZE(0x0C, R.string.gatt_insuf_key_size),
    GATT_INVALID_ATTRIBUTE_LENGTH(0x0D, R.string.gatt_invalid_attribute_length),
    GATT_INVALID_ATTR_LEN(0x0D, R.string.gatt_invalid_attr_len),
    GATT_ERR_UNLIKELY(0x0E, R.string.gatt_err_unlikely),
    GATT_INSUFFICIENT_ENCRYPTION(0x0F, R.string.gatt_insufficient_encryption),
    GATT_UNSUPPORT_GRP_TYPE(0x10, R.string.gatt_unsupport_grp_type),
    GATT_INSUF_RESOURCE(0x11, R.string.gatt_insuf_resource),
    GATT_DISCONNECTED_BY_DEVICE(0x13, R.string.gatt_disconnected_by_device),
    GATT_NO_BONDED(0x16, R.string.gatt_no_bonded),
    GATT_NO_RESSOURCES(0x80, R.string.gatt_no_ressources),
    GATT_INTERNAL_ERROR(0x81, R.string.gatt_internal_error),
    GATT_WRONG_STATE(0x82, R.string.gatt_wrong_state),
    GATT_DB_FULL(0x83, R.string.gatt_db_full),
    GATT_BUSY(0x84, R.string.gatt_busy),
    GATT_ERROR(0x85, R.string.gatt_error),
    GATT_CMD_STARTED(0x86, R.string.gatt_cmd_started),
    GATT_ILLEGAL_PARAMETER(0x87, R.string.gatt_illegal_parameter),
    GATT_PENDING(0x88, R.string.gatt_pending),
    GATT_AUTH_FAIL(0x89, R.string.gatt_auth_fail),
    GATT_MORE(0x8A, R.string.gatt_more),
    GATT_INVALID_CFG(0x8B, R.string.gatt_invalid_cfg),
    GATT_SERVICE_STARTED(0x8C, R.string.gatt_service_started),
    GATT_ENCRYPED_NO_MITM(0x8D, R.string.gatt_encryped_no_mitm),
    GATT_NOT_ENCRYPTED(0x8E, R.string.gatt_not_encrypted),
    GATT_CONNECTION_CONGESTED(0x8F, R.string.gatt_connection_congested),
    GATT_FAILURE(0x101, R.string.gatt_failure),
    GATT_UNIDENTIFIED(0x999, R.string.gatt_unidentified);

    private final Integer code;
    private final Integer message;

    private static final Map<Integer, GattStatus> lookup = new HashMap<>();

    static {
        for (GattStatus h : GattStatus.values()) {
            lookup.put(h.getCode(), h);
        }
    }

    GattStatus(Integer code, Integer message) {
        this.code = code;
        this.message = message;
    }

    public static GattStatus get(Integer code) {
        GattStatus status = lookup.get(code);
        if (status == null) {
            return GATT_UNIDENTIFIED;
        }
        return status;
    }

    public Integer getCode() {
        return code;
    }

    public Integer getMessage() {
        return message;
    }
}