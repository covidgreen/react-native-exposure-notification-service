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

import com.facebook.react.bridge.WritableMap;
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationClient;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import ie.gov.tracing.R;
import ie.gov.tracing.Tracing;
import ie.gov.tracing.common.AppExecutors;
import ie.gov.tracing.common.Events;
import ie.gov.tracing.common.ExposureConfig;
import ie.gov.tracing.nearby.riskcalculation.RiskCalculation;
import ie.gov.tracing.nearby.riskcalculation.RiskCalculationV1;
import ie.gov.tracing.nearby.riskcalculation.RiskCalculationV2;
import ie.gov.tracing.network.Fetcher;
import ie.gov.tracing.storage.ExposureEntity;
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

    private boolean isMoreRecentExposure(ExposureEntity entity) {

        ListenableFuture<List<ExposureEntity>> future = new ExposureNotificationRepository(context).getAllExposureEntitiesAsync();
        try {
            List<ExposureEntity> exposures = future.get();
            if (exposures.size() == 0) {
                return true;
            }
            ExposureEntity lastEntity = exposures.get(0);
            Calendar cal1 = Calendar.getInstance();
            cal1.setTime(new Date(lastEntity.getExposureContactDate()));
            Calendar cal2 = Calendar.getInstance();
            cal2.setTime(new Date(entity.getExposureContactDate()));
            long diffInMillies = Math.abs(cal2.getTimeInMillis() - cal1.getTimeInMillis());
            long dayDiff = TimeUnit.DAYS.convert(diffInMillies, TimeUnit.MILLISECONDS);

            if (dayDiff > 0) {
                return true;
            } else {
                return false;
            }

        } catch (Exception e) {
            return true;
        }

    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @NonNull
    @Override
    public ListenableFuture<Result> startWork() {
        Events.raiseEvent(Events.INFO, "StatueUpdatedWorker - startWork");
        Tracing.currentContext = this.context;

        final boolean simulate = getInputData().getBoolean("simulate", false);
        final int simulateDays = getInputData().getInt("simulateDays", 3);
        final String action = getInputData().getString("action");
        boolean inV2Mode = false;
        ExposureConfig config = null;

        Gson gson = new Gson();
        String configData = SharedPrefs.getString("exposureConfig", this.context);
        if (!configData.isEmpty()) {
            config = gson.fromJson(configData, ExposureConfig.class);
            inV2Mode = config.getV2Mode();
        }

        ExposureNotificationClientWrapper exposureNotificationClient = ExposureNotificationClientWrapper.get(context);
        final String token = getInputData().getString(ExposureNotificationClient.EXTRA_TOKEN);

        Events.raiseEvent(Events.INFO, "Beginning ENS result checking, v2 mode: " + inV2Mode);
        RiskCalculation risk;

        if (ExposureNotificationClient.ACTION_EXPOSURE_NOT_FOUND.equals(action)) {
            Events.raiseEvent(Events.INFO, "No keys matched, ending processing");
            return Futures.immediateFuture(Result.success());
        }
        if (inV2Mode ) {
            risk = new RiskCalculationV2(config);
        } else {
            risk = new RiskCalculationV1(repository, token);
        }

        return FluentFuture.from(risk.processKeys(context, simulate, simulateDays))
                .transform(exposureEntity -> {

                    if (exposureEntity == null) {
                        Events.raiseEvent(Events.INFO, "No exposure returned, ending");
                        return Futures.immediateFuture(true);
                    }
                    if (!isMoreRecentExposure(exposureEntity)) {
                        Events.raiseEvent(Events.INFO, "Contact event is older than previously reported events");
                        return Futures.immediateFuture(true);
                    }
                    List<ExposureEntity> exposureEntities = new ArrayList<>();

                    exposureEntities.add(exposureEntity);

                    // asynchronously update our summary table while we show notification
                    repository.upsertExposureEntitiesAsync(exposureEntities);

                    Events.raiseEvent(Events.ON_EXPOSURE, "exposureSummary - recording summary matches:"
                            + exposureEntity.matchedKeyCount() + ", duration minutes: " + exposureEntity.attenuationDurations());

                    HashMap<String, Object> payload = new HashMap<>();

                    payload.put("matchedKeys", exposureEntity.matchedKeyCount());
                    payload.put("attenuations", exposureEntity.attenuationDurations());
                    payload.put("maxRiskScore", exposureEntity.maximumRiskScore());
                    payload.put("daysSinceExposure", exposureEntity.daysSinceLastExposure());
                    payload.put("windows", exposureEntity.windowData());
                    payload.put("simulated", simulate);
                    payload.put("os", "android");
                    WritableMap version = Tracing.version(context);
                    payload.put("version", version.getString("display"));

                    Fetcher.saveMetric("CONTACT_NOTIFICATION", context, payload);
                    Fetcher.triggerCallback(exposureEntity, context, payload);

                    showNotification();
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
        Events.raiseEvent(Events.INFO, "StateUpdatedWorker.simulateExposure, " + timeDelay + ", " + numDays);

        WorkManager workManager = WorkManager.getInstance(Tracing.context);

        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(StateUpdatedWorker.class)
                .setInitialDelay(Duration.ofSeconds(timeDelay))
                .setInputData(
                        new Data.Builder().putBoolean("simulate", true)
                            .putString(ExposureNotificationClient.EXTRA_TOKEN, "dummy")
                            .putInt("simulateDays", numDays)
                            .putString("action", ExposureNotificationClient.ACTION_EXPOSURE_STATE_UPDATED)
                            .build())
                .build();
        workManager.enqueueUniqueWork("SimulateWorker", ExistingWorkPolicy.REPLACE, workRequest);
    }

}
