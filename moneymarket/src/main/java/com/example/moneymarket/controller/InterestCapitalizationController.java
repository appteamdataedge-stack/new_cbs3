package com.example.moneymarket.controller;

import com.example.moneymarket.dto.InterestCapitalizationRequestDTO;
import com.example.moneymarket.dto.InterestCapitalizationResponseDTO;
import com.example.moneymarket.service.InterestCapitalizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for Interest Capitalization operations
 */
@RestController
@RequestMapping("/api/interest-capitalization")
@RequiredArgsConstructor
@Slf4j
public class InterestCapitalizationController {

    private final InterestCapitalizationService interestCapitalizationService;

    /**
     * Capitalize accrued interest for an account
     * 
     * @param request The capitalization request
     * @param bindingResult Validation result
     * @return The capitalization response
     */
    @PostMapping
    public ResponseEntity<InterestCapitalizationResponseDTO> capitalizeInterest(
            @Valid @RequestBody InterestCapitalizationRequestDTO request,
            BindingResult bindingResult) {
        
        if (bindingResult.hasErrors()) {
            throw new com.example.moneymarket.exception.BusinessException(
                    bindingResult.getAllErrors().get(0).getDefaultMessage());
        }

        log.info("Received interest capitalization request for account: {}", request.getAccountNo());
        
        InterestCapitalizationResponseDTO response = interestCapitalizationService.capitalizeInterest(request);
        
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
