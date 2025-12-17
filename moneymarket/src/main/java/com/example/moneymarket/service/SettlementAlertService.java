package com.example.moneymarket.service;

import com.example.moneymarket.entity.SettlementGainLoss;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for Settlement Gain/Loss Alerting
 * Monitors settlement transactions and generates alerts for large gains/losses
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementAlertService {

    @Value("${settlement.alert.gain.threshold:50000}")
    private BigDecimal gainThreshold;

    @Value("${settlement.alert.loss.threshold:50000}")
    private BigDecimal lossThreshold;

    @Value("${settlement.alert.enabled:true}")
    private boolean alertsEnabled;

    /**
     * Check if a settlement transaction requires an alert
     *
     * @param settlement Settlement transaction to check
     * @return Alert object if threshold exceeded, null otherwise
     */
    public SettlementAlert checkForAlert(SettlementGainLoss settlement) {
        if (!alertsEnabled) {
            log.debug("Alerts are disabled, skipping alert check");
            return null;
        }

        if (settlement == null || settlement.getSettlementAmt() == null) {
            return null;
        }

        BigDecimal amount = settlement.getSettlementAmt();
        String type = settlement.getSettlementType();
        BigDecimal threshold = "GAIN".equals(type) ? gainThreshold : lossThreshold;

        if (amount.compareTo(threshold) >= 0) {
            log.warn("SETTLEMENT ALERT: Large {} detected - Amount: {} BDT, Threshold: {} BDT, " +
                    "Account: {}, Currency: {}, Transaction: {}",
                type, amount, threshold, settlement.getAccountNo(),
                settlement.getCurrency(), settlement.getTranId());

            return createAlert(settlement, threshold);
        }

        return null;
    }

    /**
     * Create an alert for a settlement transaction
     */
    private SettlementAlert createAlert(SettlementGainLoss settlement, BigDecimal threshold) {
        AlertSeverity severity = determineAlertSeverity(settlement.getSettlementAmt(), threshold);

        return SettlementAlert.builder()
            .alertId(generateAlertId())
            .alertTimestamp(LocalDateTime.now())
            .settlementId(settlement.getSettlementId())
            .tranId(settlement.getTranId())
            .accountNo(settlement.getAccountNo())
            .currency(settlement.getCurrency())
            .settlementType(settlement.getSettlementType())
            .settlementAmt(settlement.getSettlementAmt())
            .fcyAmt(settlement.getFcyAmt())
            .dealRate(settlement.getDealRate())
            .waeRate(settlement.getWaeRate())
            .threshold(threshold)
            .severity(severity)
            .message(buildAlertMessage(settlement, severity))
            .actionRequired(severity == AlertSeverity.CRITICAL)
            .acknowledged(false)
            .build();
    }

    /**
     * Determine alert severity based on amount and threshold
     */
    private AlertSeverity determineAlertSeverity(BigDecimal amount, BigDecimal threshold) {
        BigDecimal criticalMultiplier = new BigDecimal("5.0");
        BigDecimal highMultiplier = new BigDecimal("3.0");
        BigDecimal mediumMultiplier = new BigDecimal("2.0");

        BigDecimal criticalThreshold = threshold.multiply(criticalMultiplier);
        BigDecimal highThreshold = threshold.multiply(highMultiplier);
        BigDecimal mediumThreshold = threshold.multiply(mediumMultiplier);

        if (amount.compareTo(criticalThreshold) >= 0) {
            return AlertSeverity.CRITICAL;
        } else if (amount.compareTo(highThreshold) >= 0) {
            return AlertSeverity.HIGH;
        } else if (amount.compareTo(mediumThreshold) >= 0) {
            return AlertSeverity.MEDIUM;
        } else {
            return AlertSeverity.LOW;
        }
    }

    /**
     * Build alert message
     */
    private String buildAlertMessage(SettlementGainLoss settlement, AlertSeverity severity) {
        StringBuilder message = new StringBuilder();

        message.append(String.format("[%s] Large Settlement %s Detected\n",
            severity, settlement.getSettlementType()));

        message.append(String.format("Amount: %,.2f BDT\n", settlement.getSettlementAmt()));
        message.append(String.format("Account: %s\n", settlement.getAccountNo()));
        message.append(String.format("Currency: %s (FCY: %,.2f)\n",
            settlement.getCurrency(), settlement.getFcyAmt()));
        message.append(String.format("Deal Rate: %,.4f, WAE Rate: %,.4f\n",
            settlement.getDealRate(), settlement.getWaeRate()));
        message.append(String.format("Rate Difference: %,.4f\n",
            settlement.getDealRate().subtract(settlement.getWaeRate())));
        message.append(String.format("Transaction: %s\n", settlement.getTranId()));

        if (severity == AlertSeverity.CRITICAL) {
            message.append("\n⚠️ IMMEDIATE ACTION REQUIRED - Please review this transaction urgently");
        } else if (severity == AlertSeverity.HIGH) {
            message.append("\n⚠️ HIGH PRIORITY - Please review within 1 hour");
        }

        return message.toString();
    }

    /**
     * Generate unique alert ID
     */
    private String generateAlertId() {
        return "ALERT-" + System.currentTimeMillis() + "-" +
            (int)(Math.random() * 10000);
    }

    /**
     * Batch check for alerts on multiple settlements
     *
     * @param settlements List of settlement transactions
     * @return List of alerts generated
     */
    public List<SettlementAlert> checkBatchForAlerts(List<SettlementGainLoss> settlements) {
        List<SettlementAlert> alerts = new ArrayList<>();

        for (SettlementGainLoss settlement : settlements) {
            SettlementAlert alert = checkForAlert(settlement);
            if (alert != null) {
                alerts.add(alert);
            }
        }

        if (!alerts.isEmpty()) {
            log.warn("Generated {} alerts from {} settlements",
                alerts.size(), settlements.size());

            // Group by severity
            long criticalCount = alerts.stream()
                .filter(a -> a.getSeverity() == AlertSeverity.CRITICAL)
                .count();
            long highCount = alerts.stream()
                .filter(a -> a.getSeverity() == AlertSeverity.HIGH)
                .count();

            if (criticalCount > 0) {
                log.error("⚠️ CRITICAL: {} critical settlement alerts require immediate attention",
                    criticalCount);
            }
            if (highCount > 0) {
                log.warn("⚠️ HIGH: {} high-priority settlement alerts", highCount);
            }
        }

        return alerts;
    }

    /**
     * Get current alert thresholds
     */
    public AlertThresholds getAlertThresholds() {
        return AlertThresholds.builder()
            .gainThreshold(gainThreshold)
            .lossThreshold(lossThreshold)
            .enabled(alertsEnabled)
            .build();
    }

    /**
     * Update alert thresholds (runtime configuration)
     */
    public void updateAlertThresholds(BigDecimal newGainThreshold, BigDecimal newLossThreshold) {
        log.info("Updating alert thresholds: Gain {} -> {}, Loss {} -> {}",
            gainThreshold, newGainThreshold, lossThreshold, newLossThreshold);

        this.gainThreshold = newGainThreshold;
        this.lossThreshold = newLossThreshold;
    }

    /**
     * Enable/disable alerts
     */
    public void setAlertsEnabled(boolean enabled) {
        log.info("Alerts {}", enabled ? "ENABLED" : "DISABLED");
        this.alertsEnabled = enabled;
    }

    // ========================================
    // DTOs
    // ========================================

    @Data
    @Builder
    public static class SettlementAlert {
        private String alertId;
        private LocalDateTime alertTimestamp;
        private Long settlementId;
        private String tranId;
        private String accountNo;
        private String currency;
        private String settlementType;
        private BigDecimal settlementAmt;
        private BigDecimal fcyAmt;
        private BigDecimal dealRate;
        private BigDecimal waeRate;
        private BigDecimal threshold;
        private AlertSeverity severity;
        private String message;
        private boolean actionRequired;
        private boolean acknowledged;
        private String acknowledgedBy;
        private LocalDateTime acknowledgedAt;
        private String notes;
    }

    @Data
    @Builder
    public static class AlertThresholds {
        private BigDecimal gainThreshold;
        private BigDecimal lossThreshold;
        private boolean enabled;
    }

    public enum AlertSeverity {
        LOW,      // 1x - 2x threshold
        MEDIUM,   // 2x - 3x threshold
        HIGH,     // 3x - 5x threshold
        CRITICAL  // 5x+ threshold
    }
}
