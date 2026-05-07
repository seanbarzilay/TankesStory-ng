package mcp.edit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditLockTest {

    @Test
    void tryAcquire_unlocked_returnsTrue() {
        EditLock lock = new EditLock();
        try {
            assertTrue(lock.tryAcquire());
        } finally {
            lock.release();
        }
    }

    @Test
    void tryAcquire_alreadyHeldByOtherThread_returnsFalse() throws Exception {
        EditLock lock = new EditLock();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            assertTrue(lock.tryAcquire());
            held.countDown();
            try { release.await(); } catch (InterruptedException ignored) {}
            lock.release();
        });
        t.start();
        held.await();
        long start = System.nanoTime();
        boolean got = lock.tryAcquire();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertFalse(got);
        assertTrue(elapsedMs >= 1500, "expected ~2s wait, got " + elapsedMs + "ms");
        release.countDown();
        t.join(TimeUnit.SECONDS.toMillis(5));
    }

    @Test
    void release_withoutHolding_doesNotThrow() {
        EditLock lock = new EditLock();
        lock.release();
    }
}
