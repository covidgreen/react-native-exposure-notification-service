package ie.gov.tracing.common;

import android.content.Context;

import androidx.concurrent.futures.CallbackToFutureAdapter;

import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskToFutureAdapter {
  public static <T> ListenableFuture<T> getFutureWithTimeout(
          NfTask<T> task, long timeout, TimeUnit timeUnit, Context context, ScheduledExecutorService executor) {

      if (ApiAvailabilityCheckUtils.isHMS(context)) {
          com.huawei.hmf.tasks.Task<T> gmsTask = task.getHMSTask();
          return FluentFuture.<T>from(
                  CallbackToFutureAdapter.getFuture(
                          completer -> {
                              gmsTask.addOnCompleteListener(
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
                              return "HmsCoreTask";
                          }))
                  .withTimeout(timeout, timeUnit, executor);

      } else {
          com.google.android.gms.tasks.Task<T> gmsTask = task.getGMSTask();
          return FluentFuture.<T>from(
                  CallbackToFutureAdapter.getFuture(
                          completer -> {
                              gmsTask.addOnCompleteListener(
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
}
