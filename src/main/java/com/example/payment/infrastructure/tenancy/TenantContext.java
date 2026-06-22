package com.example.payment.infrastructure.tenancy;

public final class TenantContext {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> PARTNER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenantId, String partnerId, String correlationId) {
        TENANT_ID.set(tenantId);
        PARTNER_ID.set(partnerId);
        CORRELATION_ID.set(correlationId);
    }

    public static String getTenantId() {
        return TENANT_ID.get();
    }

    public static String getPartnerId() {
        return PARTNER_ID.get();
    }

    public static String getCorrelationId() {
        return CORRELATION_ID.get();
    }

    public static void clear() {
        TENANT_ID.remove();
        PARTNER_ID.remove();
        CORRELATION_ID.remove();
    }
}
