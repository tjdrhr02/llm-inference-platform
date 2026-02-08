package inference.testsupport;

import java.time.Duration;

public final class Polling {
  private Polling() {}

  public static void waitUntil(Duration timeout, Duration interval, Condition c) throws Exception {
    long deadlineNs = System.nanoTime() + timeout.toNanos();
    Exception last = null;
    while (System.nanoTime() < deadlineNs) {
      try {
        if (c.ok()) return;
      } catch (Exception e) {
        last = e;
      }
      Thread.sleep(interval.toMillis());
    }
    if (last != null) throw last;
    throw new AssertionError("Condition not met within " + timeout);
  }

  @FunctionalInterface
  public interface Condition {
    boolean ok() throws Exception;
  }
}

