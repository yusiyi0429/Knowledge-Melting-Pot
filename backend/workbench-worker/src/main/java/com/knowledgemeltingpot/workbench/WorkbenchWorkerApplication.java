package com.knowledgemeltingpot.workbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WorkbenchWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkbenchWorkerApplication.class, args);
    }
}
