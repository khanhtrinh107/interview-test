package com.example.demo.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.time.LocalDate;

@Data
@AllArgsConstructor
public class RewardHistoryResponse {
    private LocalDate date;
    private int rewardAmount;
}
