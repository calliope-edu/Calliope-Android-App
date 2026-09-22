package cc.calliope.mini.core.bluetooth

import android.content.Context
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import cc.calliope.mini.R
import cc.calliope.mini.utils.Constants

/**
 * The silicon class of a Calliope mini — what actually decides how it is
 * flashed. BLE cannot tell a mini 1 from a mini 2 (both nRF51), so this is
 * the finest distinction the app can make:
 *
 *  - [NRF51]: mini 1.x, 2.0, 2.1 (micro:bit v1 class) — legacy DFU.
 *  - [NRF52]: mini 3 (micro:bit v2 class) — secure DFU.
 *
 * [prefValue] is the integer persisted under
 * [Constants.CURRENT_DEVICE_VERSION] since before this enum existed (0/1/2)
 * and must not change. [labelRes] is the one user-facing name.
 */
enum class BoardGeneration(val prefValue: Int, @StringRes val labelRes: Int) {
    UNKNOWN(0, R.string.board_generation_unknown),
    NRF51(1, R.string.board_generation_nrf51),
    NRF52(2, R.string.board_generation_nrf52);

    companion object {
        @JvmStatic
        fun fromPref(value: Int): BoardGeneration = entries.firstOrNull { it.prefValue == value } ?: UNKNOWN

        /** Generation of the board picked in the pattern dialog, as detected by BondingService. */
        @JvmStatic
        fun current(context: Context): BoardGeneration = fromPref(
            PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(Constants.CURRENT_DEVICE_VERSION, UNKNOWN.prefValue),
        )

        @JvmStatic
        fun saveCurrent(context: Context, generation: BoardGeneration) {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt(Constants.CURRENT_DEVICE_VERSION, generation.prefValue)
                .apply()
        }
    }
}
