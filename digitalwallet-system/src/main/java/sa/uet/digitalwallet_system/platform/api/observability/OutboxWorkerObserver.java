package sa.uet.digitalwallet_system.platform.api.observability;

/** C07-B callback seam. Calls observe committed worker outcomes and must not drive queue state. */
public interface OutboxWorkerObserver {
    enum FailureReason { DB_ERROR, UNEXPECTED_ERROR }
    enum EventResult { DONE, RETRY_SCHEDULED, DEAD, RECLAIMED }

    void loopStarted();

    void loopStopped(boolean expected);

    void pollSucceeded();

    void pollFailed(FailureReason reason);

    void eventTransition(EventResult result);
}
