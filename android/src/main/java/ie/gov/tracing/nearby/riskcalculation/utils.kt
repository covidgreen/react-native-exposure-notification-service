package ie.gov.tracing.nearby.riskcalculation

import ie.gov.tracing.common.Events.Companion.raiseError

fun doubleArrayFromString(string: String): DoubleArray? {
    try {
        val strings = string.replace("[", "").replace("]", "").split(", ").toTypedArray()
        val result = DoubleArray(strings.size)
        for (i in result.indices) {
            result[i] = strings[i].toDouble()
        }
        return result
    } catch (ex: Exception) {
        raiseError("Cannot parse double array", ex)
    }
    return null
}