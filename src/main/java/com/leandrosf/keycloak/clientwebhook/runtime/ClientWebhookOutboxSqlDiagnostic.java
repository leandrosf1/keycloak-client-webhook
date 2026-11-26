package com.leandrosf.keycloak.clientwebhook.runtime;

import java.sql.SQLException;

/**
 * Extracts only non-sensitive JDBC diagnostics for logging. SQL text and bind
 * values are deliberately excluded from routine worker logs.
 */
final class ClientWebhookOutboxSqlDiagnostic {
    private ClientWebhookOutboxSqlDiagnostic() {}

    record Detail(int vendorCode, String sqlState, String exceptionType) {}

    static Detail from(Throwable error) {
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 24; depth++, cursor = cursor.getCause()) {
            if (cursor instanceof SQLException sql) {
                String state = sql.getSQLState();
                return new Detail(sql.getErrorCode(), state == null ? "UNKNOWN" : state,
                        cursor.getClass().getSimpleName());
            }
        }
        return new Detail(0, "UNKNOWN", error == null ? "UNKNOWN" : error.getClass().getSimpleName());
    }
}
