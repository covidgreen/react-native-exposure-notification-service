package ie.gov.tracing.nearby.riskcalculation

import com.google.android.gms.nearby.exposurenotification.ExposureWindow
import androidx.annotation.Keep

@Keep
data class WindowData (
        var date: Long,
        var calibrationConfidence: Int,
        var diagnosisReportType: Int,
        var infectiousness: Int,
        var scanData: ScanData
)

fun createWindowData (window: ExposureWindow): WindowData {
    return WindowData(window.dateMillisSinceEpoch,
            window.calibrationConfidence,
            window.reportType,
            window.infectiousness,
            ScanData())
}