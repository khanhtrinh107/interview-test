package com.example.demo.repository;

import com.example.demo.entity.TimeFrame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimeFrameRepository extends JpaRepository<TimeFrame, Integer> {
}
