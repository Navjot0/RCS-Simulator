package com.jio.rcs.operator.callback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Thin HTTP wrapper responsible for actually POSTing the DLR callback to
 * the CPaaS-registered webhook URL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackClient {

    private final RestTemplate callbackRestTemplate;

    public CallbackDeliveryResult post(String url, String jsonPayload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(jsonPayload, headers);
            ResponseEntity<String> response = callbackRestTemplate.postForEntity(url, entity, String.class);
            boolean success = response.getStatusCode().is2xxSuccessful();
            return new CallbackDeliveryResult(success, response.getStatusCode().value(), response.getBody(), null);
        } catch (RestClientException e) {
            log.warn("Callback delivery to {} failed: {}", url, e.getMessage());
            return new CallbackDeliveryResult(false, null, null, e.getMessage());
        }
    }
}
