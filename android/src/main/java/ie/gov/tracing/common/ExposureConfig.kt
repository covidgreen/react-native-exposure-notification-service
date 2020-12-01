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
        val numFilesAndroid: Int?,
        val immediateDurationWeight: Double?,
        val nearDurationWeight: Double?,
        val mediumDurationWeight: Double?,
        val otherDurationWeight: Double?,
        val infectiousnessStandardWeight: Double?,
        val infectiousnessHighWeight: Double?,
        val reportTypeConfirmedTestWeight: Double?,
        val reportTypeConfirmedClinicalDiagnosisWeight: Double?,
        val reportTypeSelfReportedWeight: Double?,
        val reportTypeRecursiveWeight: Double?,
        val daysSinceLastExposureThreshold: Int?,
        val minimumRiskScoreFullRange: Double?,
        val attenuationDurationThresholds: IntArray?,
        val contiguousMode: Boolean?,
        val v2Mode: Boolean?
)

