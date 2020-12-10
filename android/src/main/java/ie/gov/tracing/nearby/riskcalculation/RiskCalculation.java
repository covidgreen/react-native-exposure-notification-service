package ie.gov.tracing.nearby.riskcalculation;

import android.content.Context;

import com.google.common.util.concurrent.ListenableFuture;

import ie.gov.tracing.storage.ExposureEntity;

public interface RiskCalculation {
    ListenableFuture<ExposureEntity> processKeys(Context context, Boolean simulate, Integer simulateDays);
}
