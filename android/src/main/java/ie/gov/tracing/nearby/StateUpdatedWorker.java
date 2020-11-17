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
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ListenableWorker;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

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

import ie.gov.tracing.R;
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
    public static final String ACTION_LAUNCH_FROM_EXPOSURE_NOTIFICATION =
      "com.google.android.apps.exposurenotification.ACTION_LAUNCH_FROM_EXPOSURE_NOTIFICATION";

  private final Context context;
  private final ExposureNotificationRepository repository;

  public StateUpdatedWorker(
          @NonNull Context context, @NonNull WorkerParameters workerParams) {
    super(context, workerParams);
    this.context = context;
    this.repository = new ExposureNotificationRepository(context);
  }

    private static double[] doubleArrayFromString(String string) {
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


  private List<ExposureWindow> getSimulatedExposureWindows(){

      ArrayList<ExposureWindow> exposureWindows = new ArrayList<ExposureWindow>();

      int defaultAttenuationDb = 30;

      int[] infectiousnessTypes = {Infectiousness.STANDARD,Infectiousness.HIGH};

      int[] reportTypes = {ReportType.CONFIRMED_TEST ,ReportType.CONFIRMED_CLINICAL_DIAGNOSIS, ReportType.SELF_REPORT};
      int[] calibrationConfidenceTypes = {CalibrationConfidence.LOWEST,CalibrationConfidence.LOW,CalibrationConfidence.MEDIUM,CalibrationConfidence.HIGH};

      int maxMins = 5;
       int varyDb = 4;

      for(int i =0;i< 5;i++){

          ExposureWindow.Builder exposureWindowBuilder = new ExposureWindow.Builder();

          ArrayList<ScanInstance> scanInstances = new ArrayList<ScanInstance>();

          for(int k = 0;k<15;k++){
              ScanInstance.Builder scanInstanceBuilder = new ScanInstance.Builder();

              int secondsSinceLastScan = Math.max(k % maxMins, 1) * 60;
              int minAttenuationDb = defaultAttenuationDb;
              int typicalAttenuationDb = defaultAttenuationDb + (i % varyDb);

              scanInstanceBuilder.setMinAttenuationDb(minAttenuationDb);
              scanInstanceBuilder.setSecondsSinceLastScan(secondsSinceLastScan);
              scanInstanceBuilder.setTypicalAttenuationDb(typicalAttenuationDb);

              scanInstances.add(scanInstanceBuilder.build());
              }

          exposureWindowBuilder.setScanInstances(scanInstances);

          Calendar cal =  Calendar.getInstance();
          cal.add(Calendar.DATE,i * -1);
          long msSinceEpoch =cal.getTimeInMillis();
          exposureWindowBuilder.setDateMillisSinceEpoch(msSinceEpoch);

          int calibrationConfidence = calibrationConfidenceTypes[i % calibrationConfidenceTypes.length];
          exposureWindowBuilder.setCalibrationConfidence(calibrationConfidence);

          int infectiousness = infectiousnessTypes[i % infectiousnessTypes.length];
          exposureWindowBuilder.setInfectiousness(infectiousness);

          int reportType = reportTypes[i % reportTypes.length];
          exposureWindowBuilder.setReportType(reportType);

          exposureWindows.add(exposureWindowBuilder.build());

                  }

      return  exposureWindows;
              }


  @NonNull
  @Override
  public ListenableFuture<Result> startWork() {
    Tracing.currentContext = getApplicationContext();

    final boolean simulate = getInputData().getBoolean("simulate", false);

            return FluentFuture.from(TaskToFutureAdapter.getFutureWithTimeout(
                    ExposureNotificationClientWrapper.get(context).getExposureWindows(),
                    DEFAULT_API_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS,
                    AppExecutors.getScheduledExecutor()))
                    .transformAsync((exposureWindows) -> {
                        if (simulate) {
                            exposureWindows = getSimulatedExposureWindows();
                        }

                        if (exposureWindows == null || exposureWindows.size() == 0) {
                            Events.raiseEvent(Events.INFO, "exposureWindows - no exposure windows, deleting token.");
                            return;
                        }

                        if (exposureWindows.size() == 0) {
                            // No matches so we show no notification and just delete the token.
                            Events.raiseEvent(Events.INFO, "exposureSummary - no matches, deleting token.");

                        }

                        return;


          }, AppExecutors.getBackgroundExecutor())
          .transform((v) -> Result.success(), AppExecutors.getLightweightExecutor())
          .catching(Exception.class, this::processError, AppExecutors.getLightweightExecutor());



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

    public static NotificationCompat.Builder buildNotification(Context context) {
    Events.raiseEvent(Events.INFO, "show notification");
    createNotificationChannel(context);
    String packageName = context.getApplicationContext().getPackageName();
    Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
    intent.putExtra("exposureNotificationClicked", true);

    intent.setAction(ACTION_LAUNCH_FROM_EXPOSURE_NOTIFICATION);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    PendingIntent pendingIntent = PendingIntent.getActivity(context, RequestCodes.CLOSE_CONTACT, intent, 0);
        return
        new Builder(context, EXPOSURE_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_notification)
                .setContentTitle(SharedPrefs.getString("notificationTitle", context))
                .setContentText(SharedPrefs.getString("notificationDesc", context))
                .setStyle(new NotificationCompat.BigTextStyle()
                .bigText(SharedPrefs.getString("notificationDesc", context)))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentIntent(pendingIntent)
//                .setOnlyAlertOnce(true)
                .setAutoCancel(true);
    }

    public static void showNotification(Context context) {
        NotificationCompat.Builder builder = buildNotification(context);
    NotificationManagerCompat notificationManager = NotificationManagerCompat
        .from(context);
    notificationManager.notify(RequestCodes.CLOSE_CONTACT, builder.build());

        ExposureNotificationRepeater.setup(context);
  }

    public void showNotification() {
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
