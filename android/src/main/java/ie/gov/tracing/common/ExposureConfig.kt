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
        val timeThreshold: Int)