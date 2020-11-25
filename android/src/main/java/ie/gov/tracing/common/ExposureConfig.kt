package ie.gov.tracing.common

import androidx.annotation.Keep

@Keep
data class ExposureConfig(
        val minimumRiskScore: Int,
        val attenuationLevelValues: List<Int>,
        val attenuationWeight: Int,
        val daysSinceLastExposureLevelValues: List<Int>,
        val daysSinceLastExposureWeight: Int,
        val durationLevelValues: List<Int>,
        val durationWeight: Int,
        val transmissionRiskLevelValues: List<Int>,
        val transmissionRiskWeight: Int,
        val durationAtAttenuationThresholds: List<Int>?,
        val thresholdWeightings: List<Double>?,
        val timeThreshold: Int,
        val numFilesAndroid: Int,
        val immediateDurationWeight: Double,
        val nearDurationWeight: Double,
        val mediumDurationWeight: Double,
        val otherDurationWeight: Double,
        val infectiousnessStandardWeight: Double,
        val infectiousnessHighWeight: Double,
        val reportTypeConfirmedTestWeight: Double,
        val reportTypeConfirmedClinicalDiagnosisWeight: Double,
        val reportTypeSelfReportedWeight: Double,
        val reportTypeRecursiveWeight: Double,
        // Note: iOS stores this as UInt, but it is experimental in kotlin
        val reportTypeNoneMap: Int, // ENDiagnosisReportType(rawValue:  codableExposureConfiguration.reportTypeNoneMap) ?? ENDiagnosisReportType.confirmedTest
        val daysSinceLastExposureThreshold: Int,
        val minimumRiskScoreFullRange: Double,
        val attenuationDurationThresholds: List<Int>,
        val infectiousnessForDaysSinceOnsetOfSymptoms: List<Int>, // self.convertToMap(codableExposureConfiguration.infectiousnessForDaysSinceOnsetOfSymptoms)
        val contiguousMode: Boolean
)

@Keep
data class ExposureConfigContainer(val exposureConfig: ExposureConfig)
