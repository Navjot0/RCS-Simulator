package com.jio.rcs.operator.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class SwaggerConfig {

    private final ProviderProperties providerProperties;

    @Bean
    public OpenAPI operatorOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("RCS Simulator")
                        .description("Mock RCS operator/provider used in place of a real operator endpoint in "
                                + "CPaaS integration testing. Simulates accept -> queue -> submit -> deliver -> "
                                + "display lifecycle, DLRs and webhook callbacks with a self-designed provider "
                                + "API - not a mirror of any specific real operator's wire format. Never delivers "
                                + "to a real handset. Open/unauthenticated: any caller is accepted and simulated "
                                + "as the same single provider identity (" + providerProperties.getIdentity().getProviderName()
                                + ") - see the README for why.")
                        .version("1.0.0")
                        .contact(new Contact().name("RCS Simulator")));
    }
}
