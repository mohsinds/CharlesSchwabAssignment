#!/usr/bin/env bash
# Apply main branch protection after CI check exists.
# Requires: gh auth with admin on the repo.
set -euo pipefail

OWNER="${OWNER:-mohsinds}"
REPO="${REPO:-CharlesSchwabAssignment}"
BRANCH="${BRANCH:-main}"

echo "Protecting ${OWNER}/${REPO}@${BRANCH} ..."

gh api \
  --method PUT \
  -H "Accept: application/vnd.github+json" \
  "/repos/${OWNER}/${REPO}/branches/${BRANCH}/protection" \
  --input - <<'EOF'
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["CI Pipeline / test-and-quality"]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": false,
    "required_approving_review_count": 1
  },
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true
}
EOF

echo "Protection applied."
echo "Enable Pages: Settings → Pages → Source = GitHub Actions"
echo "SonarCloud gates: coverage < 80%, smells > 10, security > 0"
echo "Secret: gh secret set SONAR_TOKEN --repo ${OWNER}/${REPO}"
