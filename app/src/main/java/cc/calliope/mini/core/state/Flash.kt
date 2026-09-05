package cc.calliope.mini.core.state

/** Which transport the running flash is using. */
enum class FlashMode {
    /** Partial flashing (MakeCode/MicroPython hex, only the user program). */
    PARTIAL,
    /** Full firmware upload through Nordic DFU (legacy or secure). */
    FULL_DFU,
}

/**
 * Coarse phases of a flash, in order. Replaces the Nordic
 * `PROGRESS_*` sentinels that used to be multiplexed into the percent int.
 */
enum class FlashPhase {
    /** Preflight: reading the target, checking the hex, building the zip. */
    PREPARING,
    /** Connecting to the board. */
    CONNECTING,
    /** The board is rebooting into its bootloader / pairing mode. */
    REBOOTING,
    /** Bytes are going over the air; [FlashEvent.Progress] carries percent. */
    UPLOADING,
    /** Upload done, the board validates the image. */
    FINALIZING,
    /** Link being torn down after a successful upload. */
    DISCONNECTING,
}

/** Terminal outcome of a flash — exactly one per [AppStateRepository.beginFlash]. */
sealed interface FlashResult {
    object Success : FlashResult

    /** [code] is a GATT/DFU code, or [AppMode.Error.NO_CODE] for app-level failures. */
    data class Failure(val code: Int, val message: String?) : FlashResult
}

/** One-shot events of the running flash (replay = 0). */
sealed interface FlashEvent {
    /** Upload progress, 0..100. */
    data class Progress(val percent: Int) : FlashEvent

    /** The flash ended. The sticky [AppStateRepository.mode] is already updated. */
    data class Done(val result: FlashResult) : FlashEvent
}
