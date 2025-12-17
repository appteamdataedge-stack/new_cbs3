package com.example.moneymarket.service;

import com.example.moneymarket.dto.CreateExchangeRateRequest;
import com.example.moneymarket.dto.ExchangeRateResponse;
import com.example.moneymarket.dto.UpdateExchangeRateRequest;
import com.example.moneymarket.entity.FxRateMaster;
import com.example.moneymarket.exception.BusinessException;
import com.example.moneymarket.repository.FxRateMasterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for handling foreign exchange rates
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExchangeRateService {

    private final FxRateMasterRepository fxRateMasterRepository;

    /**
     * Get the latest mid exchange rate for a currency on a specific date
     * For BDT accounts, returns 1.0
     * For foreign currencies, fetches from fx_rate_master table
     *
     * @param currency The currency code (USD, EUR, GBP, etc.)
     * @param rateDate The date to get the rate for
     * @return The exchange rate (mid rate)
     * @throws BusinessException if rate not found for foreign currency
     */
    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.SUPPORTS)
    public BigDecimal getExchangeRate(String currency, LocalDate rateDate) {
        // BDT doesn't need conversion
        if ("BDT".equals(currency)) {
            return BigDecimal.ONE;
        }

        // Build currency pair: "USD/BDT", "EUR/BDT", etc.
        String ccyPair = currency + "/BDT";

        // Fetch latest rate on or before the given date
        FxRateMaster fxRate = fxRateMasterRepository.findLatestByCcyPairAndDate(ccyPair, rateDate)
                .orElseThrow(() -> new BusinessException(
                        "No exchange rate found for " + ccyPair + " on or before " + rateDate));

        log.debug("Found exchange rate for {}: Mid={}, Date={}", 
                ccyPair, fxRate.getMidRate(), fxRate.getRateDate());

        return fxRate.getMidRate();
    }

    /**
     * Convert foreign currency amount to local currency (BDT)
     * Formula: LCY_Amt = FCY_Amt * Exchange_Rate
     * 
     * @param fcyAmount The foreign currency amount
     * @param currency The currency code
     * @param rateDate The date to use for exchange rate
     * @return The local currency (BDT) amount
     */
    @Transactional(readOnly = true)
    public BigDecimal convertToLCY(BigDecimal fcyAmount, String currency, LocalDate rateDate) {
        if (fcyAmount == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal exchangeRate = getExchangeRate(currency, rateDate);

        // LCY_Amt = FCY_Amt * Exchange_Rate
        BigDecimal lcyAmount = fcyAmount.multiply(exchangeRate)
                .setScale(2, RoundingMode.HALF_UP);

        log.debug("Converted {} {} to {} BDT (rate: {})", 
                fcyAmount, currency, lcyAmount, exchangeRate);

        return lcyAmount;
    }

    /**
     * Get exchange rate details for a currency pair
     * 
     * @param currency The currency code
     * @param rateDate The date to get the rate for
     * @return ExchangeRateInfo containing rates and metadata
     */
    @Transactional(readOnly = true)
    public ExchangeRateInfo getExchangeRateInfo(String currency, LocalDate rateDate) {
        if ("BDT".equals(currency)) {
            return ExchangeRateInfo.builder()
                    .ccyPair("BDT/BDT")
                    .midRate(BigDecimal.ONE)
                    .buyingRate(BigDecimal.ONE)
                    .sellingRate(BigDecimal.ONE)
                    .rateDate(rateDate.atStartOfDay())
                    .build();
        }

        String ccyPair = currency + "/BDT";
        FxRateMaster fxRate = fxRateMasterRepository.findLatestByCcyPairAndDate(ccyPair, rateDate)
                .orElseThrow(() -> new BusinessException(
                        "No exchange rate found for " + ccyPair + " on or before " + rateDate));

        return ExchangeRateInfo.builder()
                .ccyPair(fxRate.getCcyPair())
                .midRate(fxRate.getMidRate())
                .buyingRate(fxRate.getBuyingRate())
                .sellingRate(fxRate.getSellingRate())
                .rateDate(fxRate.getRateDate())
                .source(fxRate.getSource())
                .build();
    }

    /**
     * DTO for exchange rate information
     */
    @lombok.Data
    @lombok.Builder
    public static class ExchangeRateInfo {
        private String ccyPair;
        private BigDecimal midRate;
        private BigDecimal buyingRate;
        private BigDecimal sellingRate;
        private java.time.LocalDateTime rateDate;
        private String source;
    }

    // ========================================
    // CRUD OPERATIONS FOR EXCHANGE RATE MANAGEMENT
    // ========================================

    /**
     * Create a new exchange rate
     *
     * @param request The exchange rate creation request
     * @return The created exchange rate response
     */
    @Transactional
    public ExchangeRateResponse createExchangeRate(CreateExchangeRateRequest request) {
        log.info("Creating exchange rate for {} on {}", request.getCcyPair(), request.getRateDate());

        // Validate currency pair format
        validateCurrencyPair(request.getCcyPair());

        // Validate rates (buying < mid < selling)
        validateRates(request.getBuyingRate(), request.getMidRate(), request.getSellingRate());

        // Check for duplicate
        LocalDate rateDate = request.getRateDate().toLocalDate();
        if (fxRateMasterRepository.existsByCcyPairAndRateDate(request.getCcyPair(), rateDate)) {
            throw new IllegalArgumentException(
                    "Exchange rate already exists for " + request.getCcyPair() + " on " + rateDate);
        }

        LocalDateTime now = LocalDateTime.now();

        FxRateMaster fxRate = FxRateMaster.builder()
                .rateDate(request.getRateDate())
                .ccyPair(request.getCcyPair())
                .midRate(request.getMidRate())
                .buyingRate(request.getBuyingRate())
                .sellingRate(request.getSellingRate())
                .source(request.getSource() != null ? request.getSource() : "MANUAL")
                .uploadedBy(request.getUploadedBy() != null ? request.getUploadedBy() : "SYSTEM")
                .createdAt(now)
                .lastUpdated(now)
                .build();

        FxRateMaster saved = fxRateMasterRepository.save(fxRate);
        log.info("Created exchange rate with ID: {}", saved.getRateId());

        return mapToResponse(saved);
    }

    /**
     * Get all exchange rates with optional filters
     *
     * @param startDate Optional start date filter
     * @param endDate Optional end date filter
     * @param ccyPair Optional currency pair filter
     * @return List of exchange rates
     */
    @Transactional(readOnly = true)
    public List<ExchangeRateResponse> getAllExchangeRates(LocalDate startDate, LocalDate endDate, String ccyPair) {
        log.info("Fetching exchange rates - startDate: {}, endDate: {}, ccyPair: {}", startDate, endDate, ccyPair);

        List<FxRateMaster> rates = new ArrayList<>();

        if (startDate != null && endDate != null && ccyPair != null) {
            // Filter by date range and currency pair
            rates = fxRateMasterRepository.findByCcyPairAndRateDateBetween(ccyPair, startDate, endDate);
        } else if (startDate != null && endDate != null) {
            // Filter by date range only
            rates = fxRateMasterRepository.findByRateDateBetween(startDate, endDate);
        } else if (ccyPair != null) {
            // Filter by currency pair only
            rates = fxRateMasterRepository.findByCcyPairOrderByRateDateDesc(ccyPair);
        } else {
            // No filters - get all
            rates = fxRateMasterRepository.findAll();
        }

        log.info("Found {} exchange rates", rates.size());
        return rates.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get exchange rate by ID
     *
     * @param id The exchange rate ID
     * @return The exchange rate response
     */
    @Transactional(readOnly = true)
    public ExchangeRateResponse getExchangeRateById(Long id) {
        log.info("Fetching exchange rate with ID: {}", id);

        FxRateMaster fxRate = fxRateMasterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exchange rate not found with ID: " + id));

        return mapToResponse(fxRate);
    }

    /**
     * Get latest exchange rate for a currency pair
     *
     * @param ccyPair The currency pair (e.g., "USD/BDT")
     * @return The latest exchange rate response
     */
    @Transactional(readOnly = true)
    public ExchangeRateResponse getLatestExchangeRate(String ccyPair) {
        log.info("Fetching latest exchange rate for: {}", ccyPair);

        FxRateMaster fxRate = fxRateMasterRepository.findFirstByCcyPairOrderByRateDateDesc(ccyPair)
                .orElseThrow(() -> new IllegalArgumentException("No exchange rate found for: " + ccyPair));

        return mapToResponse(fxRate);
    }

    /**
     * Get exchange rate by date and currency pair
     *
     * @param date The rate date
     * @param ccyPair The currency pair
     * @return The exchange rate response
     */
    @Transactional(readOnly = true)
    public ExchangeRateResponse getExchangeRateByDateAndPair(LocalDate date, String ccyPair) {
        log.info("Fetching exchange rate for {} on {}", ccyPair, date);

        FxRateMaster fxRate = fxRateMasterRepository.findByCcyPairAndRateDate(ccyPair, date)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No exchange rate found for " + ccyPair + " on " + date));

        return mapToResponse(fxRate);
    }

    /**
     * Update an existing exchange rate
     *
     * @param id The exchange rate ID
     * @param request The update request
     * @return The updated exchange rate response
     */
    @Transactional
    public ExchangeRateResponse updateExchangeRate(Long id, UpdateExchangeRateRequest request) {
        log.info("Updating exchange rate with ID: {}", id);

        FxRateMaster fxRate = fxRateMasterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exchange rate not found with ID: " + id));

        // Update only provided fields
        if (request.getMidRate() != null) {
            fxRate.setMidRate(request.getMidRate());
        }
        if (request.getBuyingRate() != null) {
            fxRate.setBuyingRate(request.getBuyingRate());
        }
        if (request.getSellingRate() != null) {
            fxRate.setSellingRate(request.getSellingRate());
        }
        if (request.getSource() != null) {
            fxRate.setSource(request.getSource());
        }
        if (request.getUploadedBy() != null) {
            fxRate.setUploadedBy(request.getUploadedBy());
        }

        // Validate rates after update
        validateRates(fxRate.getBuyingRate(), fxRate.getMidRate(), fxRate.getSellingRate());

        fxRate.setLastUpdated(LocalDateTime.now());
        FxRateMaster updated = fxRateMasterRepository.save(fxRate);

        log.info("Updated exchange rate with ID: {}", id);
        return mapToResponse(updated);
    }

    /**
     * Delete an exchange rate
     *
     * @param id The exchange rate ID
     */
    @Transactional
    public void deleteExchangeRate(Long id) {
        log.info("Deleting exchange rate with ID: {}", id);

        FxRateMaster fxRate = fxRateMasterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exchange rate not found with ID: " + id));

        // Check if this is the only rate for this currency pair on this date
        // In production, you might want to check if this rate is being used in transactions

        fxRateMasterRepository.delete(fxRate);
        log.info("Deleted exchange rate with ID: {}", id);
    }

    /**
     * Get distinct currency pairs
     *
     * @return List of distinct currency pairs
     */
    @Transactional(readOnly = true)
    public List<String> getDistinctCurrencyPairs() {
        log.info("Fetching distinct currency pairs");
        return fxRateMasterRepository.findDistinctCcyPairs();
    }

    // ========================================
    // HELPER METHODS
    // ========================================

    /**
     * Validate currency pair format
     */
    private void validateCurrencyPair(String ccyPair) {
        if (!ccyPair.matches("^[A-Z]{3}/[A-Z]{3}$")) {
            throw new IllegalArgumentException("Invalid currency pair format. Expected: XXX/XXX (e.g., USD/BDT)");
        }
    }

    /**
     * Validate exchange rates (buying <= mid <= selling)
     */
    private void validateRates(BigDecimal buyingRate, BigDecimal midRate, BigDecimal sellingRate) {
        if (buyingRate.compareTo(midRate) > 0) {
            throw new IllegalArgumentException("Buying rate cannot be greater than mid rate");
        }
        if (midRate.compareTo(sellingRate) > 0) {
            throw new IllegalArgumentException("Mid rate cannot be greater than selling rate");
        }
    }

    /**
     * Map FxRateMaster entity to ExchangeRateResponse DTO
     */
    private ExchangeRateResponse mapToResponse(FxRateMaster fxRate) {
        return ExchangeRateResponse.builder()
                .rateId(fxRate.getRateId())
                .rateDate(fxRate.getRateDate())
                .ccyPair(fxRate.getCcyPair())
                .midRate(fxRate.getMidRate())
                .buyingRate(fxRate.getBuyingRate())
                .sellingRate(fxRate.getSellingRate())
                .source(fxRate.getSource())
                .uploadedBy(fxRate.getUploadedBy())
                .createdAt(fxRate.getCreatedAt())
                .lastUpdated(fxRate.getLastUpdated())
                .build();
    }
}
