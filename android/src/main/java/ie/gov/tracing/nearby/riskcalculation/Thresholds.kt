package ie.gov.tracing.nearby.riskcalculation

data class Thresholds(
        val thresholdWeightings: IntArray,
        val timeThreshold: Int,
        val numFiles: Int,
)