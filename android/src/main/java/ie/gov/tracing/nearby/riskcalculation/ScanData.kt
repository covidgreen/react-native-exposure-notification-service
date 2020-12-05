package ie.gov.tracing.nearby.riskcalculation

data class ScanData (val buckets: IntArray = intArrayOf(0, 0, 0, 0), var exceedsThresholds: Boolean = false)