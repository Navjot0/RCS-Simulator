package com.jio.rcs.operator.callback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.UnknownHostException;

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
            return new CallbackDeliveryResult(success, response.getStatusCode().value(), response.getBody(), null, true);
        } catch (HttpStatusCodeException e) {
            // RestTemplate's default error handler throws for any non-2xx
            // response instead of returning it via postForEntity, so every
            // 4xx/5xx lands here, not in the branch above. 404 specifically
            // means the callback URL doesn't exist at that destination -
            // retrying can never succeed, so it's excluded from the retry
            // loop (see CallbackEngine.attempt). Every other status (auth
            // errors, transient 5xx, etc.) still retries exactly as before.
            boolean notFound = e.getStatusCode().value() == 404;
            log.warn("Callback delivery to {} failed: HTTP {} {}", url, e.getStatusCode().value(), e.getMessage());
            return new CallbackDeliveryResult(false, e.getStatusCode().value(), null, e.getMessage(), !notFound);
        } catch (RestClientException e) {
            // Covers connect/read timeouts, connection refused, and DNS
            // resolution failure. An UnknownHostException specifically means
            // the callback URL's host doesn't exist/can't be resolved at
            // all - same "will never succeed" reasoning as a 404, so it's
            // excluded from retry too. Everything else here (timeouts,
            // connection refused, other transient network errors) still
            // retries as before.
            boolean hostNotFound = isUnknownHost(e);
            log.warn("Callback delivery to {} failed: {}", url, e.getMessage());
            return new CallbackDeliveryResult(false, null, null, e.getMessage(), !hostNotFound);
        }
    }

    private boolean isUnknownHost(Throwable e) {
        while (e != null) {
            if (e instanceof UnknownHostException) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }
}
