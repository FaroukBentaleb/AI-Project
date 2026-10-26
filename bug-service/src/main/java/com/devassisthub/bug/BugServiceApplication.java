package com.devassisthub.bug;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BugServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BugServiceApplication.class, args);
    }
}
