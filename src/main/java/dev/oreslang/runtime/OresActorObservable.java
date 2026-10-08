package dev.oreslang.runtime;

/** Read-only subscription capability for a producer owned by one live actor. */
public final class OresActorObservable<T> extends OresObservable<T> implements AutoCloseable {
    interface Driver<T> {
        OresSubscription<T> subscribe();
        void close();
    }
    private final Driver<T> driver;
    OresActorObservable(Driver<T> driver) { this.driver = driver; }
    @Override public OresSubscription<T> subscribe() { return driver.subscribe(); }
    @Override public void close() { driver.close(); }
}

final class OresActorStream<T> extends OresStream<T> implements AutoCloseable {
    private final OresActorObservable<T> delegate;
    OresActorStream(OresActorObservable<T> delegate) { this.delegate = delegate; }
    @Override public OresSubscription<T> subscribe() { return delegate.subscribe(); }
    @Override public void close() { delegate.close(); }
}
