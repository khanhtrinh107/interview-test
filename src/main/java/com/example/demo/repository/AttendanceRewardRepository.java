package com.example.demo.repository;

import com.example.demo.entity.AttendanceReward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface AttendanceRewardRepository extends JpaRepository<AttendanceReward, Integer> {
    Optional<AttendanceReward> findAttendanceRewardsByRewardDate(LocalDate date);
}
