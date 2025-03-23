package com.example.demo.controller;

import com.example.demo.dto.response.*;
import com.example.demo.service.AttendanceRecordsService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/attendance")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AttendanceRecordController {
    AttendanceRecordsService attendanceRecordsService;

    @GetMapping("/list")
    ApiResponse<?> getListChecking(@RequestParam(name = "startDate", required = true) LocalDate startDate, @RequestParam(name = "endDate", required = true) LocalDate endDate) {
        List<AttendanceRecordResponse> responses = attendanceRecordsService.getListChecking(startDate, endDate);
        return ApiResponse.builder()
                .result(new AttendanceRecordResponses(responses))
                .build();
    }

    @GetMapping("/histories")
    ApiResponse<?> attendanceHistories() {
        List<RewardHistoryResponse> responses = attendanceRecordsService.getAllRewardHistory();
        return ApiResponse.builder()
                .result(responses)
                .build();
    }

    @PostMapping
    ApiResponse<?> markChecking() {
        return ApiResponse.builder()
                .result(attendanceRecordsService.markAttendance())
                .build();
    }
}
