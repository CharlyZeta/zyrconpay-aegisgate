#!/bin/bash

# Configuration
export VAULT_ADDR='http://localhost:8200'
export VAULT_TOKEN='myroot'

echo "Initializing HashiCorp Vault secrets for AegisGate dynamic multi-tenancy..."

# Wait for Vault to be ready
until curl -s "$VAULT_ADDR/v1/sys/health" > /dev/null; do
    echo "Waiting for Vault at $VAULT_ADDR..."
    sleep 2
done

# Enable key-value (KV) engine if not already enabled
vault secrets enable -path=secret kv-v2 2>/dev/null || echo "KV engine already enabled or configured."

# Seed secrets for merchant-alpha
vault kv put secret/merchants/merchant-alpha \
    signingKey="alpha-key-secret" \
    apiKey="alpha-api-key-999"

# Seed secrets for default-merchant
vault kv put secret/merchants/default-merchant \
    signingKey="test-secret-key-123" \
    apiKey="default-api-key-111"

echo "Vault secrets successfully seeded!"
