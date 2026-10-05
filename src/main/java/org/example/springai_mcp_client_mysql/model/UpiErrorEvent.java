package org.example.springai_mcp_client_mysql.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/** One UPI error log event posted to the REST API. */
public record UpiErrorEvent(
        LocalDateTime timestamp,
        String component,      // upi-switch | npci-gateway
        String stage,          // CBS_DEBIT | NPCI_FORWARD | ...
        String respCode,       // 51, 91, U30 ...
        String reason,
        String rrn,
        String txnId,
        BigDecimal amount,
        Map<String, Object> extra) {
}