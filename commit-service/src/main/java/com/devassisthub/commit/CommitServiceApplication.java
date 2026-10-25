package com.devassisthub.commit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CommitServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommitServiceApplication.class, args);
    }
}
