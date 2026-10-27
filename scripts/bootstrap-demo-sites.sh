#!/usr/bin/env bash
# Prepares the demo sites (M1 design D8, ADR-0006). For each site, idempotently:
#   1. creates a demo certificate authority with OpenSSL and stores it (certificate + key) in Key Vault secret
#      site-<siteId>-ca — never regenerated if present; the key only exists in a temporary directory meanwhile;
#   2. registers the CA in DPS as a verified CA certificate named site-<siteId> (DPS only accepts device chains that
#      end at a verified CA);
#   3. creates the DPS enrollment group site-<siteId> referencing that CA, with the device twin tag siteId, which
#      IoT Hub enrichment adds to every message.
# Uses the deployer's Entra identity only. Demo only: production sites use the customer's PKI.
set -euo pipefail

: "${KEY_VAULT_NAME:?}" "${DPS_NAME:?}" "${AZURE_RESOURCE_GROUP:?}"
SITES="${DEMO_SITES:-paris-01,lyon-01,marseille-01}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for site in ${SITES//,/ }; do
  secret="site-${site}-ca"
  if az keyvault secret show --vault-name "$KEY_VAULT_NAME" -n "$secret" -o none 2>/dev/null; then
    echo "[$site] CA already in Key Vault (kept)"
  else
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$WORK/$site.key" 2>/dev/null
    openssl req -x509 -new -key "$WORK/$site.key" -sha256 -days 1825 \
      -subj "/CN=${site} demo site CA/O=Smart Device Platform demo" \
      -addext "basicConstraints=critical,CA:true,pathlen:0" \
      -addext "keyUsage=critical,keyCertSign,cRLSign" \
      -addext "subjectKeyIdentifier=hash" \
      -out "$WORK/$site.crt"
    cat "$WORK/$site.crt" "$WORK/$site.key" > "$WORK/$site.bundle.pem"
    az keyvault secret set --vault-name "$KEY_VAULT_NAME" -n "$secret" --file "$WORK/$site.bundle.pem" \
      --content-type application/x-pem-file -o none
    rm -f "$WORK/$site.key" "$WORK/$site.bundle.pem"
    echo "[$site] CA generated and stored in Key Vault"
  fi

  az keyvault secret show --vault-name "$KEY_VAULT_NAME" -n "$secret" --query value -o tsv \
    | awk '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/' > "$WORK/$site-ca.pem"

  ca_name="site-$site"
  if az iot dps certificate show --dps-name "$DPS_NAME" -g "$AZURE_RESOURCE_GROUP" --certificate-name "$ca_name" -o none 2>/dev/null; then
    echo "[$site] CA already registered in DPS"
  else
    az iot dps certificate create --dps-name "$DPS_NAME" -g "$AZURE_RESOURCE_GROUP" --certificate-name "$ca_name" \
      --path "$WORK/$site-ca.pem" --verified true -o none
    echo "[$site] CA registered in DPS (verified)"
  fi

  primary="$(az iot dps enrollment-group show --dps-name "$DPS_NAME" -g "$AZURE_RESOURCE_GROUP" \
               --enrollment-id "site-$site" --auth-type login --query attestation.x509.caReferences.primary -o tsv 2>/dev/null || echo missing)"
  if [[ "$primary" == "missing" ]]; then
    az iot dps enrollment-group create --dps-name "$DPS_NAME" -g "$AZURE_RESOURCE_GROUP" \
      --enrollment-id "site-$site" --ca-name "$ca_name" \
      --initial-twin-tags "{\"siteId\":\"$site\"}" --provisioning-status enabled \
      --auth-type login -o none
    echo "[$site] DPS enrollment group created"
  elif [[ "$primary" != "$ca_name" ]]; then
    az iot dps enrollment-group update --dps-name "$DPS_NAME" -g "$AZURE_RESOURCE_GROUP" \
      --enrollment-id "site-$site" --ca-name "$ca_name" --remove-certificate true --auth-type login -o none
    echo "[$site] DPS enrollment group now references the verified CA"
  else
    echo "[$site] DPS enrollment group already exists"
  fi
done
