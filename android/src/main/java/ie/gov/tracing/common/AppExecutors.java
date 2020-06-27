package ie.gov.tracing.common;

import android.os.Process;
import android.os.StrictMode;
import android.os.StrictMode.ThreadPolicy;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * A simple static repository of {@link java.util.concurrent.Executor}s for use throughout the app.
 */
public final class AppExecutors {

  private AppExecutors() {
    // Prevent instantiation.
  }

  // Number of lightweight executor threads is dynamic. See #lightweightThreadCount()
  private static final int NUM_BACKGROUND_THREADS = 4;
  private static final ThreadPolicy POLICY =
      new ThreadPolicy.Builder().detectAll().penaltyLog().build();

  private static ListeningExecutorService lightweightExecutor;
  private static ListeningExecutorService backgroundExecutor;
  private static ListeningScheduledExecutorService scheduledExecutor;

  public static synchronized ListeningExecutorService getLightweightExecutor() {
    if (lightweightExecutor == null) {
      lightweightExecutor =
          createFixed(
              "Lightweight", lightweightThreadCount(), Process.THREAD_PRIORITY_DEFAULT);
    }
    return lightweightExecutor;
  }

  public static synchronized ListeningExecutorService getBackgroundExecutor() {
    if (backgroundExecutor == null) {
      backgroundExecutor =
          createFixed(
              "Background", backgroundThreadCount(), Process.THREAD_PRIORITY_BACKGROUND);
    }
    return backgroundExecutor;
  }

  public static synchronized ListeningScheduledExecutorService getScheduledExecutor() {
    if (scheduledExecutor == null) {
      scheduledExecutor =
          createScheduled(
                  backgroundThreadCount());
    }
    return scheduledExecutor;
  }

  private static ListeningExecutorService createFixed(
          String name, int count, int priority) {
    return MoreExecutors.listeningDecorator(
        Executors.newFixedThreadPool(count, createThreadFactory(name, priority)));
  }

  private static ListeningScheduledExecutorService createScheduled(
          int minCount) {
    return MoreExecutors.listeningDecorator(
        Executors.newScheduledThreadPool(minCount, createThreadFactory("Scheduled", Process.THREAD_PRIORITY_BACKGROUND)));
  }

  private static ThreadFactory createThreadFactory(
          String name, int priority) {
    return new ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat(name + " #%d")
        .setThreadFactory(
            runnable ->
                new Thread(
                    () -> {
                      //StrictMode.setThreadPolicy(AppExecutors.POLICY);
                      // Despite what the class name might imply, the Process class
                      // operates on the current thread.
                      Process.setThreadPriority(priority);
                      runnable.run();
                    }))
        .build();
  }

  private static int lightweightThreadCount() {
    // Always use at least two threads, so that clients can't depend on light weight executor tasks
    // executing sequentially
    return Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
  }

  private static int backgroundThreadCount() {
    return NUM_BACKGROUND_THREADS;
  }
}
