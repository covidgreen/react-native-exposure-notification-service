package ie.gov.tracing.common;

import android.content.Context;

import com.google.android.gms.common.GoogleApiAvailability;
import com.huawei.hms.api.HuaweiApiAvailability;


public class ApiAvailabilityCheckUtils {

    public static boolean isGMS(Context context) {
        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS;
    }

    public static boolean isHMS(Context context) {
        return HuaweiApiAvailability.getInstance().isHuaweiMobileServicesAvailable(context) == com.huawei.hms.api.ConnectionResult.SUCCESS;
    }

}