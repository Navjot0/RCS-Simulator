package com.jio.rcs.operator.wire;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every real provider wire profile implemented so far (Jio, Dotgo, VI)
 * fronts its messaging API with an OAuth2 client-credentials token endpoint
 * that the CPaaS provider adapter calls before sending. This simulator has
 * no real credentials to check - any client_id/client_secret is accepted -
 * so the shared response shape lives here once instead of being duplicated
 * across each wire controller's token endpoint.
 *
 * <p>The token itself is a single fixed value shared by every provider
 * profile (see {@code operator.wire.static-access-token}), not freshly
 * generated per request - this makes it possible to hardcode/whitelist a
 * known token value for manual testing (curl/Postman) or a future Bearer
 * validation layer, instead of the value changing on every call.
 */
final class OAuthTokenSupport {

    private OAuthTokenSupport() {
    }

    static Map<String, Object> simulatedToken(String accessToken) {
        Map<String, Object> token = new LinkedHashMap<>();
        token.put("access_token", accessToken);
        token.put("token_type", "Bearer");
        token.put("expires_in", 3600);
        return token;
    }
}
