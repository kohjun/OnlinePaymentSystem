package com.example.payment.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class MarketplaceNotificationWriter {

    private static final String COLUMNS = """
            notification_id, recipient_user_id, recipient_customer_id, recipient_seller_id,
            notification_type, title, message, action_path, aggregate_type, aggregate_id,
            idempotency_key, created_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private volatile String databaseProduct;

    @Transactional(propagation = Propagation.MANDATORY)
    public int insertIfAbsent(String notificationId,
                              String userId,
                              String customerId,
                              String sellerId,
                              String notificationType,
                              String title,
                              String message,
                              String actionPath,
                              String aggregateType,
                              String aggregateId,
                              String idempotencyKey,
                              LocalDateTime createdAt) {
        Object[] arguments = {
                notificationId, userId, customerId, sellerId, notificationType, title,
                message, actionPath, aggregateType, aggregateId, idempotencyKey, createdAt
        };
        String product = databaseProduct();
        if (product.contains("postgresql")) {
            return jdbcTemplate.update("INSERT INTO marketplace_notifications(" + COLUMNS + ") "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (idempotency_key) DO NOTHING", arguments);
        }
        if (product.contains("h2")) {
            return jdbcTemplate.update("MERGE INTO marketplace_notifications(" + COLUMNS + ") "
                    + "KEY(idempotency_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arguments);
        }
        throw new IllegalStateException("Unsupported marketplace notification database: " + product);
    }

    private String databaseProduct() {
        String current = databaseProduct;
        if (current != null) {
            return current;
        }
        current = jdbcTemplate.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT));
        if (current == null || current.isBlank()) {
            throw new IllegalStateException("Unable to identify marketplace notification database.");
        }
        databaseProduct = current;
        return current;
    }
}
