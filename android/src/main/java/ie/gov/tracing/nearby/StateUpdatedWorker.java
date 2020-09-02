package ie.gov.tracing.nearby;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.Builder;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Data;
import ie.gov.tracing.R;
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationClient;
import com.google.android.gms.nearby.exposurenotification.ExposureSummary;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ie.gov.tracing.Tracing;
import ie.gov.tracing.common.AppExecutors;
import ie.gov.tracing.common.Events;
import ie.gov.tracing.common.TaskToFutureAdapter;
import ie.gov.tracing.network.Fetcher;
import ie.gov.tracing.storage.ExposureEntity;
import ie.gov.tracing.storage.ExposureNotificationRepository;
import ie.gov.tracing.storage.SharedPrefs;

import static ie.gov.tracing.nearby.ProvideDiagnosisKeysWorker.DEFAULT_API_TIMEOUT;

public class StateUpdatedWorker extends ListenableWorker {
  private static final String EXPOSURE_NOTIFICATION_CHANNEL_ID =
      "ExposureNotificationCallback.EXPOSURE_NOTIFICATION_CHANNEL_ID";
  private static final String ACTION_LAUNCH_FROM_EXPOSURE_NOTIFICATION =
      "com.google.android.apps.exposurenotification.ACTION_LAUNCH_FROM_EXPOSURE_NOTIFICATION";

  private final Context context;
  private final ExposureNotificationRepository repository;

  public StateUpdatedWorker(
          @NonNull Context context, @NonNull WorkerParameters workerParams) {
    super(context, workerParams);
    this.context = context;
    this.repository = new ExposureNotificationRepository(context);
  }

  private static double [] doubleArrayFromString(String string) {
      try {
          String[] strings = string.replace("[", "").replace("]", "").split(", ");
          double[] result = new double[strings.length];
          for (int i = 0; i < result.length; i++) {
              result[i] = Double.parseDouble(strings[i]);
          }
          return result;
      } catch (Exception ex) {
          Events.raiseError("Cannot parse double array", ex);
      }
      return null;
  }

  @NonNull
  @Override
  public ListenableFuture<Result> startWork() {
    Tracing.currentContext = getApplicationContext();

    final String token = getInputData().getString(ExposureNotificationClient.EXTRA_TOKEN);
    final boolean simulate = getInputData().getBoolean("simulate", false);
    if (token == null) {
      return Futures.immediateFuture(Result.failure());
    } else {
      return FluentFuture.from(TaskToFutureAdapter.getFutureWithTimeout(
          ExposureNotificationClientWrapper.get(context).getExposureSummary(token),
          DEFAULT_API_TIMEOUT.toMillis(),
          TimeUnit.MILLISECONDS,
          AppExecutors.getScheduledExecutor()))
          .transformAsync((exposureSummary) -> {
              if (simulate) {
                ExposureSummary.ExposureSummaryBuilder builder = new ExposureSummary.ExposureSummaryBuilder();
                int[] dummyAttenuations = new int[]{30, 30, 30};
                builder.setAttenuationDurations(dummyAttenuations);
                builder.setDaysSinceLastExposure(1);
                builder.setMatchedKeyCount(1);
                builder.setMaximumRiskScore(10);
                builder.setSummationRiskScore(10);
                exposureSummary = builder.build();
              }

              if (exposureSummary == null) {
                  Events.raiseEvent(Events.INFO, "exposureSummary - no exposure summary, deleting token.");
                  return repository.deleteTokenEntityAsync(token);
              }

              if (exposureSummary.getMatchedKeyCount() == 0) {
                  // No matches so we show no notification and just delete the token.
                  Events.raiseEvent(Events.INFO, "exposureSummary - no matches, deleting token.");
                  return repository.deleteTokenEntityAsync(token);
              }

              if (exposureSummary.getMaximumRiskScore() == 0) {
                  Events.raiseEvent(Events.INFO, "exposureSummary - maximumRiskScore: " +
                          exposureSummary.getMaximumRiskScore() + ", deleting token.");
                  return repository.deleteTokenEntityAsync(token);
              }

              Events.raiseEvent(Events.INFO, "exposureSummary - maximumRiskScore: " +
                      exposureSummary.getMaximumRiskScore());

              int[] ad = exposureSummary.getAttenuationDurationsInMinutes();

              double[] tw;
              long timeThreshold;
              if (simulate) {
                  tw = doubleArrayFromString("[1, 1, 0]");
                  timeThreshold = 15;
              } else {
                  tw = doubleArrayFromString(SharedPrefs.getString("thresholdWeightings", context));
                  timeThreshold = SharedPrefs.getLong("timeThreshold", context);
              }

              if (tw == null || tw.length != 3 || timeThreshold <= 0) {
                  Events.raiseEvent(Events.INFO, "exposureSummary - timeThreshold or " +
                          "weightingThresholds not set or invalid, deleting token and aborting.");
                  return repository.deleteTokenEntityAsync(token);
              }

              Events.raiseEvent(Events.INFO, "exposureSummary - Determining if exposure durations: " +
                      Arrays.toString(ad) + ", using " + "thresholdWeightings: " + Arrays.toString(tw) +
                      ", exceeds the timeThreshold: " + timeThreshold);

              double totalTime = tw[0] * ad[0] + tw[1] * ad[1] + tw[2] * ad[2];

              if (totalTime < timeThreshold) {
                  Events.raiseEvent(Events.INFO, "exposureSummary - totalTime: " + totalTime +
                          " is less than timeThreshold: " + timeThreshold + ", ignoring and deleting token.");
                  return repository.deleteTokenEntityAsync(token);
              }

              Events.raiseEvent(Events.INFO, "exposureSummary - totalTime: " + totalTime +
                      " exceeds timeThreshold: " + timeThreshold + ", recording successful match");

              // store field as a string (otherwise we'd need a new table)
              String attenuationDurations = "";
              if (ad.length > 0) {
                  attenuationDurations = Integer.toString(ad[0]);
                  for (int i = 1; i < ad.length; i++) {
                      attenuationDurations += "," + ad[i];
                  }
              }

              List<ExposureEntity> exposureEntities = new ArrayList<>();
              ExposureEntity exposureEntity = ExposureEntity.create(
                      exposureSummary.getDaysSinceLastExposure(),
                      exposureSummary.getMatchedKeyCount(),
                      exposureSummary.getMaximumRiskScore(),
                      exposureSummary.getSummationRiskScore(),
                      attenuationDurations
              );
              exposureEntities.add(exposureEntity);

              // asynchronously update our summary table while we show notification
              repository.upsertExposureEntitiesAsync(exposureEntities);

              Events.raiseEvent(Events.ON_EXPOSURE, "exposureSummary - recording summary matches:"
                      + exposureSummary.getMatchedKeyCount() + ", duration minutes: " + attenuationDurations);
              showNotification();

              HashMap<String, Object> payload = new HashMap<>();

              payload.put("matchedKeys", exposureSummary.getMatchedKeyCount());
              payload.put("attenuations", ad);
              payload.put("maxRiskScore", exposureSummary.getMaximumRiskScore());

              Fetcher.saveMetric("CONTACT_NOTIFICATION", context, payload);
              Fetcher.triggerCallback(exposureEntity, context, payload);

              // finish by marking token as read if we have positive matchCount for token
              return repository.markTokenEntityRespondedAsync(token);
          }, AppExecutors.getBackgroundExecutor())
          .transform((v) -> Result.success(), AppExecutors.getLightweightExecutor())
          .catching(Exception.class, this::processError, AppExecutors.getLightweightExecutor());
    }
  }

  private Result processError(Exception ex) {
      HashMap<String, Object> payload = new HashMap<>();
      payload.put("description", "error receiving notification: " + ex);
      Fetcher.saveMetric("LOG_ERROR", context, payload);

      Events.raiseError("error receiving notification", ex);
      return Result.failure();
  }

  static void createNotificationChannel(Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel =
          new NotificationChannel(EXPOSURE_NOTIFICATION_CHANNEL_ID,
              context.getString(R.string.notification_channel_name),
              NotificationManager.IMPORTANCE_HIGH);
      channel.setDescription(context.getString(R.string.notification_channel_description));
      NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
      notificationManager.createNotificationChannel(channel);
    }
  }

  public static void showNotification(Context context) {
    Events.raiseEvent(Events.INFO, "show notification");
    createNotificationChannel(context);
    String packageName = context.getApplicationContext().getPackageName();
    Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
    intent.putExtra("exposureNotificationClicked", true);

    intent.setAction(ACTION_LAUNCH_FROM_EXPOSURE_NOTIFICATION);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    PendingIntent pendingIntent = PendingIntent.getActivity(context, RequestCodes.CLOSE_CONTACT, intent, 0);
    NotificationCompat.Builder builder =
        new Builder(context, EXPOSURE_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_notification)
                .setContentTitle(SharedPrefs.getString("notificationTitle", context))
                .setContentText(SharedPrefs.getString("notificationDesc", context))
                .setStyle(new NotificationCompat.BigTextStyle()
                .bigText(SharedPrefs.getString("notificationDesc", context)))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true);
    NotificationManagerCompat notificationManager = NotificationManagerCompat
        .from(context);
    notificationManager.notify(RequestCodes.CLOSE_CONTACT, builder.build());
  }

  private void showNotification() {
    showNotification(context);
  }

   public static void simulateExposure(Long timeDelay) {
        Events.raiseEvent(Events.INFO, "StateUpdatedWorker.simulateExposure");

        WorkManager workManager = WorkManager.getInstance(Tracing.context);

        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(StateUpdatedWorker.class)
                .setInitialDelay(Duration.ofSeconds(timeDelay))
                .setInputData(
                        new Data.Builder().putBoolean("simulate", true).putString(ExposureNotificationClient.EXTRA_TOKEN, "dummy")
                                .build())
                .build();
        workManager.enqueueUniqueWork("SimulateWorker", ExistingWorkPolicy.REPLACE, workRequest);
   }
}