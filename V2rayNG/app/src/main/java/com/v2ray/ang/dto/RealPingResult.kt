package com.v2ray.ang.dto

import java.io.Serializable

data class RealPingResult(
    val guid: String,
    val samples: LongArray
) : Serializable
