package com.zyrconpay.aegisgate.common.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.zyrconpay.aegisgate.common.service.VaultMerchantCredentialService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class MerchantProfileCache {

    private final VaultMerchantCredentialService credentialService;
    private final LoadingCache<String, Map<String, String>> cache;

    public MerchantProfileCache(VaultMerchantCredentialService credentialService) {
        this.credentialService = credentialService;
        
        // Local cache utilizing refreshAfterWrite for non-blocking asynchronous refreshes
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(300, TimeUnit.SECONDS)
                .refreshAfterWrite(240, TimeUnit.SECONDS)
                .maximumSize(500)
                .build(credentialService::getMerchantCredentials);
    }

    /**
     * Retrieves merchant secrets, utilizing local cache lookup before hitting Vault.
     *
     * @param merchantId the unique tenant merchant ID
     * @return the credential values map
     */
    public Map<String, String> getCredentials(String merchantId) {
        return cache.get(merchantId);
    }

    /**
     * Forces eviction of a tenant's cached credentials.
     *
     * @param merchantId the tenant identifier
     */
    public void invalidate(String merchantId) {
        cache.invalidate(merchantId);
    }
}
