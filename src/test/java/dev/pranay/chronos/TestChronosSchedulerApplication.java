package dev.pranay.chronos;

import org.springframework.boot.SpringApplication;

public class TestChronosSchedulerApplication {

	public static void main(String[] args) {
		SpringApplication.from(ChronosSchedulerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
