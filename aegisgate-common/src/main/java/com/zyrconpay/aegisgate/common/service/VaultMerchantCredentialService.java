package com.zyrconpay.aegisgate.common.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class VaultMerchantCredentialService {

    private static final Logger log = LoggerFactory.getLogger(VaultMerchantCredentialService.class);

    private final VaultTemplate vaultTemplate;
    private final Environment environment;

    // Optional autowire to support configurations without Vault active
    public VaultMerchantCredentialService(
            @Autowired(required = false) VaultTemplate vaultTemplate,
            Environment environment) {
        this.vaultTemplate = vaultTemplate;
        this.environment = environment;
    }

    /**
     * Fetches merchant secrets (api keys, signing keys) from Vault.
     * Fallback to static secrets if Vault is unavailable or in mock environment.
     *
     * @param merchantId the lookup merchant identifier
     * @return map of merchant credentials
     */
    public Map<String, String> getMerchantCredentials(String merchantId) {
        if (vaultTemplate != null) {
            try {
                String path = "secret/merchants/" + merchantId;
                log.info("Fetching credentials from Vault path: {}", path);
                VaultResponse response = vaultTemplate.read(path);
                if (response != null && response.getData() != null) {
                    Map<String, String> creds = new HashMap<>();
                    response.getData().forEach((k, v) -> creds.put(k, String.valueOf(v)));
                    return creds;
                }
            } catch (Exception e) {
                log.warn("Failed to retrieve credentials from Vault for merchant: {}. Falling back.", merchantId, e);
            }
        }
        
        // Static fallback for testing and mock profiles
        return getMockCredentials(merchantId);
    }

  private Map<String, String> getMockCredentials(String merchantId) {
    List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
    if (activeProfiles.contains("dev") || activeProfiles.contains("test")) {
      Map<String, String> mockCreds = new HashMap<>();
      if ("merchant-alpha".equals(merchantId)) {
        mockCreds.put("signingKey", "alpha-key-secret");
        mockCreds.put("apiKey", "alpha-api-key-999");
      } else if ("default-merchant".equals(merchantId)) {
        mockCreds.put("signingKey", "test-secret-key-123");
        mockCreds.put("apiKey", "default-api-key-111");
      }
      return mockCreds;
    }
    return Collections.emptyMap();
  }
}
