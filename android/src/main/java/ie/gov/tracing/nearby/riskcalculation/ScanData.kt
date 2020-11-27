package ie.gov.tracing.nearby.riskcalculation

data class ScanData (val buckets: MutableList<Int>, var exceedsThresholds: Boolean)