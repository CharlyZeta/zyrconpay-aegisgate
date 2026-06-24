package com.zyrconpay.aegisgate.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.zyrconpay.aegisgate.orchestrator", "com.zyrconpay.aegisgate.common"})
public class AegisGateOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(AegisGateOrchestratorApplication.class, args);
    }
}
