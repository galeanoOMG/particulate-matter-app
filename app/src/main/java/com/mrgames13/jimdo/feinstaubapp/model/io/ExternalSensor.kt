/*
 * Copyright © Marc Auberer 2017 - 2020. All rights reserved
 */

package com.mrgames13.jimdo.feinstaubapp.model.io

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExternalSensor (
    @SerialName("i") val chipId: Long,
    @SerialName("la") val latitude: Double,
    @SerialName("lo") val longitude: Double,
    @SerialName("a") val active: Boolean
)

@Serializable
data class ExternalSensorSyncPackage (
    val ids: List<String>,
    val update: List<ExternalSensor>
)