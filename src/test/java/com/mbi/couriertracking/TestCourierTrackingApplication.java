package com.mbi.couriertracking;

import org.springframework.boot.SpringApplication;

public class TestCourierTrackingApplication {

    public static void main(String[] args) {
        SpringApplication.from(CourierTrackingApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
