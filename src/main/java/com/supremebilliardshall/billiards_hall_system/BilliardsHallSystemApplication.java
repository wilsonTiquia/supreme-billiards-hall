package com.supremebilliardshall.billiards_hall_system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling drives one job: the 05:00 end-of-day session auto-close.
@SpringBootApplication
@EnableScheduling
public class BilliardsHallSystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(BilliardsHallSystemApplication.class, args);
	}

}
