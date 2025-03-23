package com.example.demo.configuration;

import com.example.demo.constant.PredefinedRole;
import com.example.demo.entity.AttendanceReward;
import com.example.demo.entity.Role;
import com.example.demo.entity.TimeFrame;
import com.example.demo.entity.User;
import com.example.demo.repository.AttendanceRewardRepository;
import com.example.demo.repository.RoleRepository;
import com.example.demo.repository.TimeFrameRepository;
import com.example.demo.repository.UserRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Configuration
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
public class ApplicationInitConfig {

    PasswordEncoder passwordEncoder;

    @NonFinal
    static final String ADMIN_USER_NAME = "admin";

    @NonFinal
    static final String ADMIN_PASSWORD = "admin";

    @Bean
    @ConditionalOnProperty(
            prefix = "spring",
            value = "datasource.driverClassName",
            havingValue = "com.mysql.cj.jdbc.Driver")
    ApplicationRunner applicationRunner(UserRepository userRepository, RoleRepository roleRepository,
                                        AttendanceRewardRepository attendanceRewardRepository, TimeFrameRepository timeFrameRepository) {
        log.info("Initializing application.....");
        return args -> {
            if (userRepository.findByUsername(ADMIN_USER_NAME).isEmpty()) {
                roleRepository.save(Role.builder()
                        .name(PredefinedRole.USER_ROLE)
                        .description("User role")
                        .build());

                Role adminRole = roleRepository.save(Role.builder()
                        .name(PredefinedRole.ADMIN_ROLE)
                        .description("Admin role")
                        .build());

                var roles = new HashSet<Role>();
                roles.add(adminRole);

                User user = User.builder()
                        .username(ADMIN_USER_NAME)
                        .password(passwordEncoder.encode(ADMIN_PASSWORD))
                        .roles(roles)
                        .build();

                userRepository.save(user);
                log.warn("Admin user has been created with default password: admin, please change it");
            }

            LocalDate today = LocalDate.now();
            LocalDate firstDayOfMonth = today.withDayOfMonth(1);
            LocalDate lastDayOfMonth = today.withDayOfMonth(today.lengthOfMonth());

            if (attendanceRewardRepository.count() == 0) {
                List<AttendanceReward> rewards = new ArrayList<>();
                int rewardAmount = 1;
                for (LocalDate date = firstDayOfMonth; !date.isAfter(lastDayOfMonth); date = date.plusDays(1)) {
                    rewards.add(AttendanceReward.builder()
                            .rewardDate(date)
                            .rewardAmount(rewardAmount)
                            .build());
                    rewardAmount++;
                }
                attendanceRewardRepository.saveAll(rewards);
                log.info("Inserted attendance rewards for the month.");
            }

            if (timeFrameRepository.count() == 0) {
                List<TimeFrame> timeFrames = List.of(
                        TimeFrame.builder().start(LocalTime.of(9, 0)).end(LocalTime.of(11, 0)).build(),
                        TimeFrame.builder().start(LocalTime.of(19, 0)).end(LocalTime.of(21, 0)).build()
                );
                timeFrameRepository.saveAll(timeFrames);
                log.info("Inserted time frames for check-in.");
            }

            log.info("Application initialization completed .....");
        };
    }
}
