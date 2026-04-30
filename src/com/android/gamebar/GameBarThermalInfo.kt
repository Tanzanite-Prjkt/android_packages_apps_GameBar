/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-FileCopyrightText: 2026 putrazxyo13
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.gamebar

import android.content.Context
import android.os.PowerManager

object GameBarThermalInfo {

    private const val TAG = "GameBarThermalInfo"

    data class ThermalState(
        val level: Int,
        val label: String,
        val colorHex: String
    )

    private val STATE_NONE = ThermalState(
        PowerManager.THERMAL_STATUS_NONE,
        "Normal",
        "#4CAF50"
    )

    fun getThermalState(context: Context): ThermalState {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return STATE_NONE

        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE ->
                STATE_NONE
            PowerManager.THERMAL_STATUS_LIGHT ->
                ThermalState(PowerManager.THERMAL_STATUS_LIGHT, "Light", "#8BC34A")
            PowerManager.THERMAL_STATUS_MODERATE ->
                ThermalState(PowerManager.THERMAL_STATUS_MODERATE, "Moderate", "#FFC107")
            PowerManager.THERMAL_STATUS_SEVERE ->
                ThermalState(PowerManager.THERMAL_STATUS_SEVERE, "Severe", "#FF9800")
            PowerManager.THERMAL_STATUS_CRITICAL ->
                ThermalState(PowerManager.THERMAL_STATUS_CRITICAL, "Critical", "#F44336")
            PowerManager.THERMAL_STATUS_EMERGENCY ->
                ThermalState(PowerManager.THERMAL_STATUS_EMERGENCY, "Emergency", "#D50000")
            PowerManager.THERMAL_STATUS_SHUTDOWN ->
                ThermalState(PowerManager.THERMAL_STATUS_SHUTDOWN, "Shutdown", "#B71C1C")
            else -> STATE_NONE
        }
    }

    fun getThermalLabel(context: Context): String {
        return getThermalState(context).label
    }
}
