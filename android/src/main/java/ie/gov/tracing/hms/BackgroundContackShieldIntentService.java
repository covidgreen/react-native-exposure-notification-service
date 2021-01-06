package ie.gov.tracing.hms;

import android.app.IntentService;
import android.content.Intent;

import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.android.gms.nearby.exposurenotification.ExposureNotificationClient;
import com.huawei.hms.contactshield.ContactShield;
import com.huawei.hms.contactshield.ContactShieldCallback;
import com.huawei.hms.contactshield.ContactShieldEngine;

import ie.gov.tracing.nearby.StateUpdatedWorker;

public class BackgroundContackShieldIntentService extends IntentService {
    private static final String TAG = "ContactShieldPendIntent";

    private ContactShieldEngine contactEngine;

    public BackgroundContackShieldIntentService() {
        super(TAG);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        contactEngine = ContactShield.getContactShieldEngine(BackgroundContackShieldIntentService.this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            contactEngine.handleIntent(intent, new ContactShieldCallback() {
                @Override
                public void onHasContact(String token) {
                    WorkManager.getInstance(BackgroundContackShieldIntentService.this).enqueue(
                            new OneTimeWorkRequest.Builder(StateUpdatedWorker.class)
                                    .setInputData(new Data.Builder().putString(ExposureNotificationClient.EXTRA_TOKEN, token)
                                            .build()).build());
                }

                @Override
                public void onNoContact(String token) {
                }
            });
        }
    }
}