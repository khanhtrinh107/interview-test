package com.example.demo.repository;

import com.example.demo.entity.AttendanceRecords;
import com.example.demo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceRecordsRepository extends JpaRepository<AttendanceRecords, Integer> {
    boolean existsAttendanceRecordsByUserAndAndAttendanceDate(User user, LocalDate attendanceDate);

    List<AttendanceRecords> findByUserId(String user);

    @Query(value = "SELECT * FROM attendance_records WHERE user_id = :userId AND DATE(attendance_date) BETWEEN DATE(:startDate) AND DATE(:endDate)",
            nativeQuery = true)
    List<AttendanceRecords> findByUserIdAndAttendanceDateBetween(
            @Param("userId") String userId,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate
    );

}
