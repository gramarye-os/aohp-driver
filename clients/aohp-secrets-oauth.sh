#!/bin/bash
# aohp-secrets-oauth.sh — reference OpenClaw launcher integration (docs/CREDENTIALS.md item 6).
#
# Drop-in for the Anthropic credential step of aohp-secrets, demonstrating the §0 portability
# contract: BROKER FIRST, own-credential FALLBACK. The gateway starts the same whether or not the
# broker is present, so the image still runs on a plain phone / off AOHP.
#
# Emits shell 'export' lines on stdout, consumed by:  eval "$(aohp-secrets-oauth.sh anthropic)"
set -euo pipefail
provider="${1:-anthropic}"

# 1) Try the broker (cred-get prints "HEADER_NAME<TAB>HEADER_VALUE", non-zero on any miss).
if line="$(cred-get "$provider" gateway_launch 2>/dev/null)"; then
  name="${line%%$'\t'*}"; value="${line#*$'\t'}"
  case "$name" in
    authorization)          printf 'export ANTHROPIC_AUTH_TOKEN=%q\n' "${value#Bearer }" ;;
    x-api-key)              printf 'export ANTHROPIC_API_KEY=%q\n' "$value" ;;
    x-subscription-token)   printf 'export BRAVE_API_KEY=%q\n' "$value" ;;
    *)                      printf 'export %s=%q\n' "${name//-/_}" "$value" ;;
  esac
  exit 0
fi

# 2) Fallback: the app's own credential source (today's Keystore SecretStore via 'aohp secret').
if key="$(aohp secret get ANTHROPIC_API_KEY 2>/dev/null)" && [ -n "$key" ]; then
  printf 'export ANTHROPIC_API_KEY=%q\n' "$key"
  exit 0
fi

echo "aohp-secrets-oauth: no credential for $provider (broker miss + no stored key)" >&2
exit 1
