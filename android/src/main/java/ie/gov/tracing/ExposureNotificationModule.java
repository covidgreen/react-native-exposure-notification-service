package ie.gov.tracing;

import android.app.Activity;
import android.os.Build;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.google.android.gms.common.GoogleApiAvailability;

import org.jetbrains.annotations.NotNull;

import ie.gov.tracing.common.Events;

public class ExposureNotificationModule extends ReactContextBaseJavaModule {
    private static int playServicesVersion = 0;
    private static int apiError = 0;

    public boolean nearbyNotSupported(){
        return !(apiError == 0 && Build.VERSION.SDK_INT >= 23);
    }

    private boolean sdkNotSupported() {
        if(Build.VERSION.SDK_INT < 23) return true;
        return false;
    }

    // after update performed this should be called
    public void setApiError(int errorCode) {
        apiError = errorCode;
    }

    // after update performed this should be called
    public void setPlayServicesVersion(int version) {
        playServicesVersion = version;
    }
    public int getPlayServicesVersion() { return playServicesVersion; }

    //@SuppressWarnings("WeakerAccess")
    ExposureNotificationModule(ReactApplicationContext reactContext) {
        super(reactContext);

        try {
            GoogleApiAvailability gps = GoogleApiAvailability.getInstance();
            playServicesVersion = gps.getApkVersion(reactContext.getApplicationContext());

            Events.raiseEvent(Events.INFO,
                    "Play Services Version: " + playServicesVersion
                            + ", Android version: " +  Build.VERSION.SDK_INT
            );

        } catch(Exception ex) {
            Events.raiseError("ExposureNotification - Unable to get play services version", ex);
        }
        Tracing.init(reactContext, this);
    }

    public Activity getActivity() {
        return getCurrentActivity();
    }

    @NotNull
    @Override
    public String getName() {
        return "ExposureNotificationModule";
    }

    @ReactMethod
    public void configure(ReadableMap params) {
        if(nearbyNotSupported()) return;
        Tracing.configure(params);
    }

    @ReactMethod
    public void start(Promise promise) {
        if(nearbyNotSupported()){
            promise.resolve(false);
            return;
        }
        Tracing.start(promise);
    }

    @ReactMethod
    public void stop() {
        if(nearbyNotSupported()) return;
        Tracing.stop();
    }

    @ReactMethod
    public void isSupported(Promise promise) {
        Tracing.isSupported(promise);
    }

    @ReactMethod
    public void exposureEnabled(Promise promise) {
        if(nearbyNotSupported()){
            promise.resolve(false);
            return;
        }
        Tracing.exposureEnabled(promise);
    }

    @ReactMethod
    public void tryEnable(Promise promise) {
        Tracing.exposureEnabled(promise);
    }

    @ReactMethod
    public void checkExposure(Boolean readExposureDetails, Boolean skipTimeCheck) {
        if(nearbyNotSupported()) return;
        Tracing.checkExposure(readExposureDetails);
    }

    @ReactMethod
    public void authoriseExposure(Promise promise) {
        if(nearbyNotSupported()){
            promise.resolve(false);
            return;
        }
        Tracing.authoriseExposure(promise);
    }

    @ReactMethod
    public void isAuthorised(Promise promise) {
        if(nearbyNotSupported()){
            promise.resolve("unavailable");
            return;
        }
        Tracing.isAuthorised(promise);
    }

    @ReactMethod
    public void deleteAllData(Promise promise) {
        if(nearbyNotSupported()){
            promise.resolve(true);
            return;
        }
        Tracing.deleteData(promise);
    }

    @ReactMethod
    public void deleteExposureData(Promise promise) {
        if(nearbyNotSupported()){
            promise.resolve(true);
            return;
        }
        Tracing.deleteExposureData(promise);
    }

    @ReactMethod
    public void getDiagnosisKeys(Promise promise) {
        if(nearbyNotSupported()){
            promise.resolve(Arguments.createArray());
            return;
        }
        Tracing.getDiagnosisKeys(promise);
    }

    @ReactMethod
    public void getCloseContacts(Promise promise) {
        if(nearbyNotSupported()){
            promise.resolve(Arguments.createArray());
            return;
        }

        Tracing.getCloseContacts(promise);
    }

    @ReactMethod
    public void getLogData(Promise promise) {
        Tracing.getLogData(promise);
    }

    @ReactMethod
    public void status(Promise promise) {
        if(nearbyNotSupported()) {
            Tracing.setExposureStatus("unavailable", "apiError: " + apiError);
        }
        Tracing.getExposureStatus(promise);
    }

    @ReactMethod
    public void triggerUpdate(Promise promise) {
        Tracing.triggerUpdate(promise);
    }

    @ReactMethod
    public void canSupport(Promise promise) {
        if(sdkNotSupported()) {
            promise.resolve(false);
            return;
        }
        promise.resolve(true);
    }
}
