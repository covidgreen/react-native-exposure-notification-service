package ie.gov.tracing.nearby;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;

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

import org.jetbrains.annotations.NotNull;

import java.net.URL;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import ie.gov.tracing.R;
import ie.gov.tracing.Tracing;
import ie.gov.tracing.common.AppExecutors;
import ie.gov.tracing.common.Events;
import ie.gov.tracing.common.ExposureConfig;
import ie.gov.tracing.nearby.riskcalculation.RiskCalculation;
import ie.gov.tracing.nearby.riskcalculation.RiskCalculationV1;
import ie.gov.tracing.nearby.riskcalculation.RiskCalculationV2;
import ie.gov.tracing.network.Certificate;
import ie.gov.tracing.network.Fetcher;
import ie.gov.tracing.network.PublishG;
import ie.gov.tracing.network.PublishNF;
import ie.gov.tracing.network.VerifyG;
import ie.gov.tracing.network.VeriyNF;
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

    @NonNull
    @Override
    public ListenableFuture<Result> startWork() {
        try {
            Events.raiseEvent(Events.INFO, "StatueUpdatedWorker - startWork");
            Tracing.currentContext = this.context;

            final boolean simulate = getInputData().getBoolean("simulate", false);
            final int simulateDays = getInputData().getInt("simulateDays", 3);
            final boolean sendChaff = getInputData().getBoolean("sendChaff", false);
            final String action = getInputData().getString("action");
            boolean inV2Mode = false;
            boolean chaffEnabled = false;
            int chaffWindow = 5;
            ExposureConfig config = null;

            Gson gson = new Gson();
            String configData = SharedPrefs.getString("exposureConfig", this.context);
            if (!configData.isEmpty()) {
                config = gson.fromJson(configData, ExposureConfig.class);
                inV2Mode = config.getV2Mode();
                chaffEnabled = config.getChaffEnabled();
                chaffWindow = config.getChaffWindow();
            }
            boolean finalChaffEnabled = chaffEnabled;
            int finalChaffWindow = chaffWindow;

            ExposureNotificationClientWrapper exposureNotificationClient = ExposureNotificationClientWrapper.get(context);
            final String token = getInputData().getString(ExposureNotificationClient.EXTRA_TOKEN);

            Events.raiseEvent(Events.INFO, "Beginning ENS result checking, v2 mode: " + inV2Mode);
            RiskCalculation risk;

            if (ExposureNotificationClient.ACTION_EXPOSURE_NOT_FOUND.equals(action)) {
                Events.raiseEvent(Events.INFO, "No keys matched, ending processing");
                generateChaffRequest(sendChaff, finalChaffWindow, finalChaffEnabled);
                return Futures.immediateFuture(Result.success());
            }
            if (inV2Mode) {
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
                    .transform(done -> {
                        generateChaffRequest(sendChaff, finalChaffWindow, finalChaffEnabled);
                        return Result.success(); // all done, do tidy ups here
                    }, AppExecutors.getLightweightExecutor())
                    .catching(Exception.class, this::processError, AppExecutors.getLightweightExecutor());
        } catch(Exception e) {
            Events.raiseError("Error in StateUpdateWorker", e);
            return Futures.immediateFuture(Result.success());
        }

    }

    @NotNull
    private String getPublishUrl() {
        try {
            String keyServerUrl = SharedPrefs.getString("keyServerUrl", context);
            String h = new URL(keyServerUrl).getHost();
            String[] parts = h.split("\\.");
            String[] doms = Arrays.copyOfRange(parts, 1, parts.length);
            return "https://" + TextUtils.join(".", doms) + "/v1";
        } catch(Exception e) {
            return SharedPrefs.getString("serverUrl", context);
        }

    }

    private void generateChaffRequest(boolean sendChaff, int chaffWindow, boolean chaffEnabled) {
        // if chaff time
        long chaffTime = SharedPrefs.getLong("nextChaff", context);
        String keyServerType = SharedPrefs.getString("keyServerType", context);
        String serverUrl = SharedPrefs.getString("serverUrl", context);
        String publishServerUrl = SharedPrefs.getString("publishServerUrl", context);

        if (publishServerUrl.isEmpty()) {
            if (keyServerType.equals("nearform")) {
                publishServerUrl = serverUrl;
            } else {
                publishServerUrl = getPublishUrl();
            }
        }

        if (chaffTime == 0) {
            chaffTime = generateNextChaffTime(context, chaffWindow);
            SharedPrefs.setLong("nextChaff", chaffTime, context);
        }
        Events.raiseEvent(Events.INFO, "Chaff  requests " + sendChaff + ", " + chaffEnabled + ", " + chaffWindow + ", " + chaffTime);
        if (sendChaff || (chaffEnabled && System.currentTimeMillis() > chaffTime)) {
            Events.raiseEvent(Events.INFO, "Sending chaff request");
            sendChaffVerify(context, keyServerType, serverUrl);
            // insert time delay here to simulate user
            Random rand = new Random();
            int randomDelay = rand.nextInt((20 - 3) + 1) + 3;
            Timer timer = new Timer("chaffpublish");
            String finalPublishServerUrl = publishServerUrl;
            TimerTask publishTask = new TimerTask() {
                public void run() {
                    Events.raiseEvent(Events.INFO, "Continuing chaff request after simulated delay");
                    if (keyServerType.equals("google")) {
                        Events.raiseEvent(Events.INFO, "Continuing chaff request with cert call");
                        sendChaffCertificate(context, serverUrl);
                    }

                    Events.raiseEvent(Events.INFO, "Continuing chaff request with publish call");
                    sendChaffPublish(context, keyServerType, serverUrl, finalPublishServerUrl);
                    SharedPrefs.setLong("nextChaff", generateNextChaffTime(context, chaffWindow), context);
                }
            };
            timer.schedule(publishTask, randomDelay * 1000);
        }
    }

    private String randomString(int min, int max) {
        final String letters = "abcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom rand = new SecureRandom();
        final char[] symbols = letters.toCharArray();
        Random random = new Random();
        int dataSize = random.nextInt((max - min) + 1) + min;
        final char[] buf = new char[dataSize];

        for (int idx = 0; idx < buf.length; ++idx)
            buf[idx] = symbols[rand.nextInt(symbols.length)];

        return new String(buf);
    }

    private void sendChaffVerify(Context context, String keyServerType, String serverUrl) {
        if (keyServerType.equals("nearform")) {
            VeriyNF verify = new VeriyNF(randomString(256, 256), randomString(512, 1024));
            Fetcher.postOnThread("/exposures/verify", new Gson().toJson(verify), context, true, serverUrl, true, true);
        } else {
            VerifyG verify = new VerifyG(randomString(8, 8), randomString(512, 1024));
            Fetcher.postOnThread("/verify", new Gson().toJson(verify), context, true, serverUrl, true, true);
        }

    }

    private void sendChaffCertificate(Context context, String serverUrl) {
        Certificate cert = new Certificate(randomString(44, 44), randomString(512, 1024), randomString(44, 44));
        Fetcher.postOnThread("/certificate", new Gson().toJson(cert), context, true, serverUrl, true, true);
    }

    private void sendChaffPublish(Context context, String keyServerType, String serverUrl, String publishServerUrl) {
        Random random = new Random();
        ArrayList<Map<String, Object>> exposures = new ArrayList<>();
        int dataSize = random.nextInt((14 - 1) + 1) + 1;

        if (keyServerType.equals("nearform")) {
            for (int i = 0; i < dataSize; i++) {
                HashMap<String, Object> exposure = new HashMap<>();
                exposure.put("keyData", randomString(16, 16));
                exposure.put("rollingPeriod", "144");
                exposure.put("rollingStartNumber", 1234567);
                exposure.put("transmissionRiskLevel", 1);
                exposures.add(exposure);
            }
            PublishNF pub = new PublishNF(randomString(16, 16), "android", randomString(128, 128), exposures, randomString(512, 1204));
            Fetcher.postOnThread("/exposures", new Gson().toJson(pub), context, true, serverUrl, true, true);
        } else {
            for (int i = 0; i < dataSize; i++) {
                HashMap<String, Object> exposure = new HashMap<>();
                exposure.put("key", randomString(16, 16));
                exposure.put("rollingPeriod", "144");
                exposure.put("rollingStartNumber", 1234567);
                exposure.put("transmissionRisk", 1);
                exposures.add(exposure);
            }
            PublishG pub = new PublishG(randomString(16, 16), "my.health.id", randomString(128, 128), 1234567, "", false, exposures, randomString(512, 1204));
            Fetcher.postOnThread("/publish", new Gson().toJson(pub), context, true, publishServerUrl, false, false);
        }

    }

    private long generateNextChaffTime(Context context, int chaffWindow) {
        Random rand = new Random();
        if (chaffWindow == 0) {
            chaffWindow = 5;
        }
        int randomOffset = rand.nextInt((chaffWindow - 1) + 1) + 1;

        Calendar today = Calendar.getInstance();
        today.add(Calendar.DATE, randomOffset);

        return today.getTimeInMillis();
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
                            .putBoolean("sendChaff", true)
                            .putString("action", ExposureNotificationClient.ACTION_EXPOSURE_STATE_UPDATED)
                            .build())
                .build();

        workManager.enqueueUniqueWork("SimulateWorker", ExistingWorkPolicy.REPLACE, workRequest);
    }

}
