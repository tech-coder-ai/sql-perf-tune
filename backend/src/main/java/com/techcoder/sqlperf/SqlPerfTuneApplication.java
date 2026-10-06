package com.techcoder.sqlperf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SqlPerfTuneApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlPerfTuneApplication.class, args);
    }
}
