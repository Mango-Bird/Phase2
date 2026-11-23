package sa.uet.digitalwallet_system.platform.api.outbox;

public interface EventHandlerPort {
    String eventType();
    void handle(EventEnvelope envelope);
}
