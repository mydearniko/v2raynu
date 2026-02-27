package com.v2ray.ang.dto

data class ServerAffiliationInfo(
    var testDelayMillis: Long = 0L,
    var testDelaySamples: MutableList<Long> = mutableListOf(),
    var ipCheckA: String? = null,
    var ipCheckB: String? = null
) {
    fun getSortedTestDelaySamples(): List<Long> {
        return testDelaySamples.sortedWith { a, b ->
            when {
                a < 0L && b < 0L -> 0
                a < 0L -> 1
                b < 0L -> -1
                else -> a.compareTo(b)
            }
        }
    }

    fun getTestDelayString(): String {
        if (testDelaySamples.isNotEmpty()) {
            return getSortedTestDelaySamples().joinToString(" / ") { "${it}ms" }
        }

        if (testDelayMillis == 0L) {
            return ""
        }
        return "${testDelayMillis}ms"
    }
}
