package ie.gov.tracing.nearby.riskcalculation
import androidx.annotation.Keep

@Keep
data class ScanData (val buckets: IntArray = intArrayOf(0, 0, 0, 0), val weightedBuckets: IntArray = intArrayOf(0, 0, 0, 0), var exceedsThresholds: Boolean = false, var numScans: Int = 0)