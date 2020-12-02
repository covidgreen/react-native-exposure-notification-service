package ie.gov.tracing.nearby;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
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
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;

import java.time.Duration;
import java.util.HashMap;

import ie.gov.tracing.R;
import ie.gov.tracing.Tracing;
import ie.gov.tracing.common.AppExecutors;
import ie.gov.tracing.common.Events;
import ie.gov.tracing.common.ExposureConfig;
import ie.gov.tracing.nearby.riskcalculation.RiskCalculation;
import ie.gov.tracing.nearby.riskcalculation.RiskCalculationV1;
import ie.gov.tracing.nearby.riskcalculation.RiskCalculationV2;
import ie.gov.tracing.network.Fetcher;
import ie.gov.tracing.storage.ExposureNotificationRepository;
import ie.gov.tracing.storage.SharedPrefs;

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

    @RequiresApi(api = Build.VERSION_CODES.N)
    @NonNull
    @Override
    public ListenableFuture<Result> startWork() { // FIXME change the order
        Tracing.currentContext = getApplicationContext();
        Events.raiseEvent(Events.INFO, "Beging ENS result checking");
        final boolean simulate = getInputData().getBoolean("simulate", false);
        final int simulateDays = getInputData().getInt("simulateDays", 3);
        Gson gson = new Gson();
        final ExposureConfig config = gson.fromJson(SharedPrefs.getString("exposureConfig", Tracing.currentContext), ExposureConfig.class);
        ExposureNotificationClientWrapper exposureNotificationClient = ExposureNotificationClientWrapper.get(context);
        final String token = getInputData().getString(ExposureNotificationClient.EXTRA_TOKEN);

        RiskCalculation risk;

        if (config.getV2Mode()) {
            risk = new RiskCalculationV2(config);
        }
        else {
            risk = new RiskCalculationV1(repository, token);
        }

        return FluentFuture.from(risk.processKeys(context, simulate, simulateDays))
                .transform(showNotification -> {
                    if (showNotification) {
                        showNotification();
                    }
                    return Futures.immediateFuture(true);
                }, AppExecutors.getLightweightExecutor())
                .transform(done -> Result.success(), // all done, do tidy ups here
                        AppExecutors.getLightweightExecutor())
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

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void simulateExposure(Long timeDelay, Integer numDays) {
        Events.raiseEvent(Events.INFO, "StateUpdatedWorker.simulateExposure");

        WorkManager workManager = WorkManager.getInstance(Tracing.context);

        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(StateUpdatedWorker.class)
                .setInitialDelay(Duration.ofSeconds(timeDelay))
                .setInputData(
                        new Data.Builder().putBoolean("simulate", true).putString(ExposureNotificationClient.EXTRA_TOKEN, "dummy").putInt("numDays", numDays)
                                .build())
                .build();
        workManager.enqueueUniqueWork("SimulateWorker", ExistingWorkPolicy.REPLACE, workRequest);
    }

}
