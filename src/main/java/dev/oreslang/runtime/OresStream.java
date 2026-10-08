package dev.oreslang.runtime;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** A pull stream with exactly one subscription. Observable permits multiple subscriptions. */
public abstract class OresStream<T> extends OresObservable<T> {
    private OresSubscription<T> readerSubscription;

    /**
     * A Stream has one underlying subscription, but readers may hand off
     * that subscription by releasing their lease. This preserves the cursor
     * and does not restart an actor producer or cancel pending work.
     */
    public final synchronized OresSubscription.Reader<T> getReader() {
        if (readerSubscription == null) {
            readerSubscription = subscribe();
        }
        return readerSubscription.getReader();
    }

    public static <T> OresStream<T> fromValues(List<? extends T> values) {
        OresObservable<T> source = OresObservable.fromValues(values);
        return new OresStream<>() {
            private final AtomicBoolean subscribed = new AtomicBoolean();
            @Override public OresSubscription<T> subscribe() {
                if (!subscribed.compareAndSet(false, true))
                    throw new IllegalStateException("Stream permits exactly one subscription");
                return source.subscribe();
            }
        };
    }
}
