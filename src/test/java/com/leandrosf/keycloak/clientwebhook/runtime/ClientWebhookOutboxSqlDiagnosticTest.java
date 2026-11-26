package com.leandrosf.keycloak.clientwebhook.runtime;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class ClientWebhookOutboxSqlDiagnosticTest {
    @Test
    void extractsNestedOracleVendorCodeWithoutLoggingSql() {
        SQLException sql = new SQLException("sensitive statement", "42000", 942);
        RuntimeException wrapped = new RuntimeException("jpa failure", new RuntimeException(sql));
        var detail = ClientWebhookOutboxSqlDiagnostic.from(wrapped);
        assertEquals(942, detail.vendorCode());
        assertEquals("42000", detail.sqlState());
        assertEquals("SQLException", detail.exceptionType());
        assertFalse(detail.toString().contains("sensitive statement"));
    }

    @Test
    void unknownErrorsProduceSafeMetadata() {
        var detail = ClientWebhookOutboxSqlDiagnostic.from(new IllegalStateException("secret value"));
        assertEquals(0, detail.vendorCode());
        assertEquals("UNKNOWN", detail.sqlState());
        assertEquals("IllegalStateException", detail.exceptionType());
        assertFalse(detail.toString().contains("secret value"));
    }
}
