package com.zyrconpay.aegisgate.ingress;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.zyrconpay.aegisgate.ingress", "com.zyrconpay.aegisgate.common"})
public class AegisGateIngressApplication {

    public static void main(String[] args) {
        SpringApplication.run(AegisGateIngressApplication.class, args);
    }
}
