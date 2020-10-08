package ie.gov.tracing.nearby;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.android.gms.nearby.exposurenotification.ExposureNotificationClient;

import ie.gov.tracing.Tracing;
import ie.gov.tracing.common.Events;

public class ExposureNotificationBroadcastReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    Tracing.currentContext = context;
    String action = intent.getAction();
    Events.raiseEvent(Events.INFO, "received event from exposure: " + action);
    WorkManager workManager = WorkManager.getInstance(context);
    if (ExposureNotificationClient.ACTION_EXPOSURE_STATE_UPDATED.equals(action)) {
      String token = intent.getStringExtra(ExposureNotificationClient.EXTRA_TOKEN);
      workManager.enqueue(
          new OneTimeWorkRequest.Builder(StateUpdatedWorker.class)
              .setInputData(
                  new Data.Builder().putString(ExposureNotificationClient.EXTRA_TOKEN, token)
                      .build())
              .build());
    }
  }
}
