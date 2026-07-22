package com.jio.rcs.operator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the RCS Simulator.
 *
 * This application simulates the provider/operator side of an RCS
 * integration. It is consumed by a CPaaS platform exactly like a real
 * RCS operator endpoint would be - the CPaaS only needs to point its base
 * URL here. It never delivers a message to a real handset; it only emulates
 * accept/queue/submit/deliver/display lifecycle, DLRs and webhook callbacks.
 *
 * Open/unauthenticated by design: this is a local test double for
 * CPaaS-side integration testing, not a service ever meant to run reachable
 * from anywhere but your own machine. Any caller is accepted and simulated
 * as the same single provider identity (see operator.identity.* in
 * application.properties) - there is no client registry, no bearer token,
 * no per-agent routing.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan
public class RcsSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(RcsSimulatorApplication.class, args);
    }
}
