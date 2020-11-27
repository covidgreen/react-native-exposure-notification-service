package ie.gov.tracing.nearby.riskcalculation

data class Thresholds(
        val thresholdWeightings: List<Int>,
        val timeThreshold: Int,
        val numFiles: Int,
        val contiguousMode: Boolean
)