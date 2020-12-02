package ie.gov.tracing.common

import androidx.annotation.Keep

@Keep
data class ExposureConfig(
        val minimumRiskScore: Int,
        val attenuationLevelValues: IntArray,
        val attenuationWeight: Int,
        val daysSinceLastExposureLevelValues: IntArray,
        val daysSinceLastExposureWeight: Int,
        val durationLevelValues: IntArray,
        val durationWeight: Int,
        val transmissionRiskLevelValues: IntArray,
        val transmissionRiskWeight: Int,
        val durationAtAttenuationThresholds: IntArray?,
        val thresholdWeightings: DoubleArray?,
        val timeThreshold: Int,
        val numFilesAndroid: Int = 12,
        val immediateDurationWeight: Double = 1.0,
        val nearDurationWeight: Double = 1.0,
        val mediumDurationWeight: Double = 1.0,
        val otherDurationWeight: Double = 1.0,
        val infectiousnessStandardWeight: Double? = 1.0,
        val infectiousnessHighWeight: Double? = 1.0,
        val reportTypeConfirmedTestWeight: Double? = 1.0,
        val reportTypeConfirmedClinicalDiagnosisWeight: Double? = 1.0,
        val reportTypeSelfReportedWeight: Double? = 1.0,
        val reportTypeRecursiveWeight: Double? = 1.0,
        val daysSinceLastExposureThreshold: Int? = 0,
        val minimumRiskScoreFullRange: Double? = 1.0,
        val attenuationDurationThresholds: IntArray = intArrayOf(50, 70, 90),
        val contiguousMode: Boolean = false,
        val v2Mode: Boolean = false
)

