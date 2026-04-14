package com.gcvisualizer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GcVisualizerApplication {
    public static void main(String[] args) {
        SpringApplication.run(GcVisualizerApplication.class, args);
    }
}
