package com.contractnotemanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ContractNoteManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContractNoteManagerApplication.class, args);
    }
}
