package sa.uet.digitalwallet_system.platform.api.outbox;

public enum OutboxStatus {
    PENDING,
    CLAIMED,
    DONE,
    DEAD
}
