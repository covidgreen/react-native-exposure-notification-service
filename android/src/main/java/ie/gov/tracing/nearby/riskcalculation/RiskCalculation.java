package ie.gov.tracing.nearby.riskcalculation;

import android.content.Context;

import com.google.common.util.concurrent.ListenableFuture;

public interface RiskCalculation {
    ListenableFuture<Boolean> processKeys(Context context, Boolean simulate, Integer simulateDays);
}
