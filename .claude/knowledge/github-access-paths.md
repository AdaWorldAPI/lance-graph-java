# GitHub access paths — three credentials, one rate-limit identity trap

> READ BY: any session doing PR babysitting, review-thread resolution,
> or hitting a GitHub 403/RATE_LIMIT in this environment.
> Measured 2026-08-28 (PR #47/#48 review cycle), every row exercised
> live — none of this is inferred.

## The core fact most sessions get wrong

There are THREE ways out of this container to GitHub, and they carry
only TWO identities:

| path | credential | identity |
|---|---|---|
| MCP `mcp__github__*` tools | the GitHub App session credential | **user ID 200276742** |
| direct `api.github.com` with `GH_TOKEN` (proxy-bypassed: `curl --noproxy '*'` / Python `ProxyHandler({})`) | the env token (`ghp_…`, strip the wrapping quotes) | **the SAME user ID 200276742** |
| through the session proxy (default env, NO Authorization header you author) | the proxy attaches its OWN credential | **a different identity** |

Consequence: when the account hits a rate limit, **switching from MCP to
pygithub/curl-with-GH_TOKEN changes nothing** — same identity, same
limit. The tesseract-rs "a 403 is usually the proxy" lesson is about
*authorization* 403s; it does NOT transfer to *rate* limits. The only
path with a different budget is the proxy's own credential.

## What each path can actually do (measured)

| operation | MCP | direct GH_TOKEN | session proxy |
|---|---|---|---|
| REST reads (PRs, comments) | ✅ (until limited) | ✅ (until limited) | ✅ `200` |
| REST writes (review-comment **replies**: `POST /pulls/{n}/comments/{id}/replies`) | ✅ | ✅ | ✅ **works — posted live** |
| GraphQL, arbitrary queries | ✅ | ✅ | ❌ "only the pinned set of PR-review operations is served" |
| GraphQL `resolveReviewThread` mutation | ✅ (via `resolve_review_thread`) | ✅ | ❌ rejected — NOT in the pinned set |
| git fetch/push | n/a | ✅ (one-shot token URL rules apply) | ✅ (plain `git push origin`; proxy carries auth) |

So **review-thread resolution is the one operation with no fallback**:
it is GraphQL-only, the proxy's pinned set excludes the mutation, and
both non-proxy paths share the limited identity. When the account is
limited, the only correct move is to wait for the reset and re-arm a
check-in — do not burn turns cycling transports.

## Recognizing a SECONDARY (abuse) limit

Signature, observed live: `GET /rate_limit` reports **5000/5000
remaining** while GraphQL simultaneously returns
`RATE_LIMIT: API rate limit already exceeded for user ID …`. The quota
endpoint reflects only the primary budget; secondary limits are
invisible there. The `reset` timestamp the quota endpoint names is
still the best available estimate of the cool-off (observed: the limit
held until roughly that time). Do not retry in a loop — secondary
limits extend under hammering.

## Proxy-path mechanics that save a session ten minutes

- Plain `curl https://api.github.com/...` with the default env goes
  THROUGH the proxy and gets the proxy credential automatically — do
  not add an `Authorization` header; authoring one replaces the good
  credential with the limited one.
- The proxy's GraphQL rejection message suggests `gh api` — `gh` is not
  installed here; plain REST via curl/urllib is the equivalent.
- Replies posted via the proxy land as `claude[bot]`. CodeRabbit
  auto-skips engaging with other bots' replies ("Skipped: comment is
  from another GitHub bot") — expected, not a failure.
- Token hygiene rules (never in URLs, never printed, strip the wrapping
  quotes inline) are unchanged and live in the sibling repos'
  CLAUDE.md files (MedCare-rs "GH_TOKEN" section is the canonical
  statement); this doc adds the identity/limit map, not new hygiene.
