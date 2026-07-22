package com.jio.rcs.operator.service;

import com.jio.rcs.operator.config.ProviderProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Backs POST /v1/capability/check. Since nothing is persisted, "is this
 * number RCS capable" can't be looked up from a real subscriber database -
 * instead the result is derived deterministically from a hash of the phone
 * number so repeated checks against the same number are stable for the
 * life of the process, weighted by operator.capability.capable-percentage.
 */
@Service
@RequiredArgsConstructor
public class CapabilityService {

    private final ProviderProperties providerProperties;

    public boolean isRcsCapable(String phoneNumber) {
        int bucket = Math.floorMod(stableHash(phoneNumber), 100);
        return bucket < providerProperties.getCapability().getCapablePercentage();
    }

    private int stableHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            int result = 0;
            for (int i = 0; i < 4; i++) {
                result = (result << 8) | (bytes[i] & 0xFF);
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available on the JDK; this is unreachable.
            return value.hashCode();
        }
    }
}
