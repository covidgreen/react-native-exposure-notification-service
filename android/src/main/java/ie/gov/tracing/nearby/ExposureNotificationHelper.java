package ie.gov.tracing.nearby;

import androidx.lifecycle.LifecycleObserver;
import ie.gov.tracing.common.Events;
import ie.gov.tracing.Tracing;
import ie.gov.tracing.common.AppExecutors;
import ie.gov.tracing.common.TaskToFutureAdapter;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import org.checkerframework.checker.nullness.compatqual.NullableDecl;
import org.jetbrains.annotations.NotNull;
import org.threeten.bp.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;


public class ExposureNotificationHelper implements LifecycleObserver {

  public interface Callback {
    void onFailure(Throwable t);
    void onSuccess(String status);
  }

  private static final Duration API_TIMEOUT = Duration.ofSeconds(10);

  private final Callback callback;

  public ExposureNotificationHelper(Callback callback) {
    this.callback = callback;
  }

  public void startExposure() {
      AtomicReference<String> status = new AtomicReference<>("started"); //default
      FluentFuture.from(isEnabled())
        .transformAsync(
            isEnabled -> {
                if (isEnabled != null && isEnabled) {
                    status.set("already started");
                    return Futures.immediateFuture(null);
                }
                Events.raiseEvent(Events.INFO, "starting exposure tracing...");
                return start();
            }, AppExecutors.getLightweightExecutor())
        .addCallback(new FutureCallback<Void>() {

            @Override
            public void onSuccess(@NullableDecl Void result) {
                callback.onSuccess(status.toString());
            }

            @Override
            public void onFailure(@NotNull Throwable t) {
                callback.onFailure(t);
            }
        }, MoreExecutors.directExecutor());
  }

  public void stopExposure() {
      AtomicReference<String> status = new AtomicReference<>("stopped"); // default
      FluentFuture.from(isEnabled())
        .transformAsync(
            isEnabled -> {
              if (isEnabled != null && !isEnabled) {
                  status.set("already stopped");
                  return Futures.immediateFuture(null);
              }
              Events.raiseEvent(Events.INFO, "stopping exposure tracing...");
              return stop();
            }, AppExecutors.getLightweightExecutor())
        .addCallback(
            new FutureCallback<Void>() {

              @Override
              public void onSuccess(@NullableDecl Void result) {
                callback.onSuccess(status.toString());
              }

              @Override
              public void onFailure(@NotNull Throwable t) {
                callback.onFailure(t);
              }

            }, MoreExecutors.directExecutor());
  }

  public static ListenableFuture<Boolean> isEnabled() {
    return TaskToFutureAdapter.getFutureWithTimeout(
        ExposureNotificationClientWrapper.get(Tracing.reactContext).isEnabled(),
        API_TIMEOUT.toMillis(),
        TimeUnit.MILLISECONDS,
        AppExecutors.getScheduledExecutor());
  }

  private static ListenableFuture<Void> start() {
    return TaskToFutureAdapter.getFutureWithTimeout(
        ExposureNotificationClientWrapper.get(Tracing.reactContext).start(),
        API_TIMEOUT.toMillis(),
        TimeUnit.MILLISECONDS,
        AppExecutors.getScheduledExecutor());
  }

  private static ListenableFuture<Void> stop() {
    return TaskToFutureAdapter.getFutureWithTimeout(
        ExposureNotificationClientWrapper.get(Tracing.reactContext).stop(),
        API_TIMEOUT.toMillis(),
        TimeUnit.MILLISECONDS,
        AppExecutors.getScheduledExecutor());
  }

  public static ListenableFuture<Long> getDeviceENSVersion() {
    return TaskToFutureAdapter.getFutureWithTimeout(
            ExposureNotificationClientWrapper.get(Tracing.reactContext).getDeviceENSVersion(),
            API_TIMEOUT.toMillis(),
            TimeUnit.MILLISECONDS,
            AppExecutors.getScheduledExecutor());
  }
}
