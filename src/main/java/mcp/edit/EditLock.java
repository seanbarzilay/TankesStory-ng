package mcp.edit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class EditLock {

    private static final long TIMEOUT_SECONDS = 2;

    public static final EditLock INSTANCE = new EditLock();

    private final ReentrantLock lock = new ReentrantLock();

    public boolean tryAcquire() {
        try {
            return lock.tryLock(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release() {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
