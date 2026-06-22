package com.example.payment.presentation.dto.request;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class RaffleDrawRequest {
    @Min(value = 1, message = "winnerCount must be greater than 0")
    private Integer winnerCount;

    private String seed;
    private String operatorId;
}
