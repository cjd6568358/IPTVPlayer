package com.your.iptv.util;

import android.os.Handler;
import android.os.Looper;

/** 指数退避重试:1s→2s→4s→8s→15s→30s(封顶)。reset 由成功路径调用。 */
public class RetryPolicy {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mAttempt;
    private static final long[] BACKOFF = {1000, 2000, 4000, 8000, 15000, 30000};

    public interface Sink { void fire(long delayMs); }

    public void schedule(final Sink sink) {
        long delay = BACKOFF[Math.min(mAttempt, BACKOFF.length - 1)];
        mAttempt++;
        mHandler.postDelayed(new Runnable() {
            @Override public void run() { sink.fire(delay); }
        }, delay);
    }

    public void reset() { mAttempt = 0; }

    public int attempts() { return mAttempt; }
}
