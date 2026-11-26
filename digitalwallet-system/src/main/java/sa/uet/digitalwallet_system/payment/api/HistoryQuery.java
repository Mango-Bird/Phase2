package sa.uet.digitalwallet_system.payment.api;

public record HistoryQuery(String cursor, Integer limit, String type, String from, String to) {}
