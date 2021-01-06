package ie.gov.tracing.common;

import androidx.concurrent.futures.CallbackToFutureAdapter;

import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskToFutureAdapter {
  public static <T> ListenableFuture<T> getFutureWithTimeout(
          Task<T> task, long timeout, TimeUnit timeUnit, ScheduledExecutorService executor) {
    return FluentFuture.<T>from(
        CallbackToFutureAdapter.getFuture(
            completer -> {
              task.addOnCompleteListener(
                  executor,
                  completed -> {
                    try {
                      if (completed.isCanceled()) {
                        completer.setCancelled();
                      } else if (completed.getException() != null) {
                        completer.setException(completed.getException());
                      } else {
                        completer.set(completed.getResult());
                      }
                    } catch (Exception ex) {
                      completer.setException(ex);
                    }
                  });
              return "GmsCoreTask";
            }))
        .withTimeout(timeout, timeUnit, executor);
  }
}
