package cc.calliope.mini.core.state

/**
 * What the app is doing right now, as one sticky value.
 *
 * The live-editor "control" session is deliberately NOT a mode: it is an
 * orthogonal flag ([AppStateRepository.control]) because a control session
 * and a flash can be reasoned about independently.
 */
sealed interface AppMode {

    /** Nothing running. */
    object Idle : AppMode

    /** Short non-flash work: bonding a new board, copying a hex to USB. */
    object Busy : AppMode

    /** A flash is running; [phase] and [mode] are updated as it progresses. */
    data class Flashing(val phase: FlashPhase, val mode: FlashMode) : AppMode

    /**
     * The last operation failed. Sticky so a recreated screen (rotation
     * during the failure) still sees it; cleared by the next operation.
     * [code] is a GATT/DFU error code, or [NO_CODE] for app-level failures.
     */
    data class Error(val code: Int, val message: String?) : AppMode {
        companion object {
            const val NO_CODE = 0
        }
    }

    companion object {
        /** Bonding and pre-upload flash phases show the spinning FAB. */
        @JvmStatic
        fun isSpinning(mode: AppMode): Boolean =
            mode is Busy || (mode is Flashing && mode.phase < FlashPhase.UPLOADING)
    }
}
