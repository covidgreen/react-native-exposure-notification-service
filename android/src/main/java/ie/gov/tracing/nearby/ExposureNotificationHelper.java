package ie.gov.tracing.nearby;

import android.content.Context;

import androidx.lifecycle.LifecycleObserver;

import ie.gov.tracing.common.ApiAvailabilityCheckUtils;
import ie.gov.tracing.common.Events;
import ie.gov.tracing.Tracing;
import ie.gov.tracing.common.AppExecutors;
import ie.gov.tracing.common.ExposureClientWrapper;
import ie.gov.tracing.common.TaskToFutureAdapter;
import ie.gov.tracing.hms.ContactShieldWrapper;

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
import static ie.gov.tracing.common.ApiAvailabilityCheckUtils.isGMS;
import static ie.gov.tracing.common.ApiAvailabilityCheckUtils.isHMS;

public class ExposureNotificationHelper implements LifecycleObserver {

  public interface Callback {
    void onFailure(Throwable t);
    void onSuccess(String status);
  }

  private static final Duration API_TIMEOUT = Duration.ofSeconds(10);

  private final Callback callback;
  private final ExposureClientWrapper client;
  private final Context context;

  public ExposureNotificationHelper(Callback callback, Context context) {
      this.callback = callback;
      this.context = context;
      if (ApiAvailabilityCheckUtils.isHMS(context)) {
          client = ContactShieldWrapper.get(context);
      } else {
          client = ExposureNotificationClientWrapper.get(context);
      }
  }


}
