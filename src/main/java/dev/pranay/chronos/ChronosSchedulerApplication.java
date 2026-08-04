package dev.pranay.chronos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ChronosSchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChronosSchedulerApplication.class, args);
    }

}
