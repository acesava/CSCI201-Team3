# AGENTS.md

Instructions for every AI coding agent working in this repo (Claude Code, Codex, Cursor, Copilot, Gemini, Windsurf, Aider, ...).
Humans: this is also the team workflow. If your personal agent config contradicts this file, this file wins.

## Project

Expense tracker for CSCI 201.
- `frontend/`: React 19 + Vite 7, plain JSX, Node 22. Cloudflare Pages deploys it from `main`.
- `backend/`: Java 21 AWS Lambda (`edu.usc.csci201.team3.Handler::handleRequest`), Maven wrapper, JUnit 5. GitHub Actions deploys it to production on every push to `main` that touches `backend/**`.
- `infrastructure/`: Python 3 boto3 scripts plus CloudFormation rendered from Python. CI never deploys it; the owner runs it by hand.
- `database/`: notes only. No schema yet.

There is no local backend server. The frontend talks to the shared deployed API.

## Commands

Run from the repo root. All must exit 0 before you open a PR.

```bash
python3 -m unittest discover -s infrastructure/tests -v     # infra tests (offline)
(cd frontend && npm ci && npm run build)                     # frontend build (Node 22)
(cd backend && ./mvnw --batch-mode verify)                   # backend build + tests (JDK 21)
```

Single test: `(cd backend && ./mvnw -Dtest=HandlerTest test)`.
Frontend dev: `cd frontend && cp .env.example .env.local && npm run dev`, then open http://localhost:5173. Use `localhost`, not `127.0.0.1`, because of CORS.
DynamoDB integration test (optional, needs DynamoDB Local): `DYNAMODB_LOCAL_ENDPOINT=http://127.0.0.1:18080 ./backend/mvnw -f backend/pom.xml verify`.

## Workflow (follow in order)

1. **Branch.** Start from an up-to-date `main`: `git switch main && git pull && git switch -c <type>/<short-kebab-description>`.
   `<type>` is one of `feat`, `fix`, `infra`, `docs`, `test`, `chore`. Example: `feat/expense-list-endpoint`.
   Never commit on `main`.
2. **Change.** Keep the diff scoped to the task. No drive-by refactors.
3. **Test.** For new behavior or a bug fix, add or update a test in the matching suite (see Testing).
4. **Self-check.** Run all three commands under Commands. Fix failures. Do not skip, delete, or `@Disabled` tests to get green.
5. **Commit.** Imperative, sentence case, no prefix, no trailing period, at most 72 chars. Example: `Add shared per-IP burst limits before Java routes`. Optional prose body explaining why.
6. **Push + PR.** `git push -u origin HEAD`, then `gh pr create --base main` using the PR body format below (the repo's PR template has the same sections).
7. **Wait for CI.** Run `gh pr checks --watch`. The `infrastructure`, `frontend`, and `backend` checks must all pass, and the Cloudflare Pages check must pass if you touched `frontend/`. If any is failing or pending, do not merge.
8. **Merge.** Squash-merge only, after CI is green and a teammate has approved. Delete the branch after merge.
   Agents: do not merge. Stop after step 7 and tell the human the PR is ready.

**When stuck:** if a check keeps failing, a rule here is unclear, or the task needs something under "Ask the human first", stop and explain what is blocking you. Never work around a rule in this file.

### PR body format

```
## Problem
<what is wrong or missing>

## Change
<what this PR does>

## Validation
<commands you ran and their result, e.g. "mvnw verify: Tests run: 10, Failures: 0">

## Not changed / not covered
<explicit limits, follow-ups>
```

## Testing

- Java: JUnit 5 in `backend/src/test/java/edu/usc/csci201/team3/`. Fake dependencies with lambdas through the nested interfaces (e.g. `CounterStore`) and a fixed `Clock`. No mocking libraries.
- Python: `unittest` + `unittest.mock` in `infrastructure/tests/`. Inject boto3 clients so tests never touch AWS.
- Frontend: no test suite yet; `npm run build` must pass. If you touched `frontend/`, manually check that the page loads and calls `/health`.

## Code style

Match the surrounding code. Specifics that differ from defaults:

**Java**: 4-space indent, Java 21 features (records, `switch ->`, pattern `instanceof`). Make classes package-private and `final` unless they must be public. Inject dependencies through a package-private constructor; the public no-arg constructor delegates to it:

```java
public Handler() {
    this(DynamoIpCounter.production());
}

Handler(IpRateLimiter limiter) {
    this.limiter = limiter;
}
```

Fail closed: if a dependency such as the rate limiter fails, return 503. Never run the business route anyway.
Log to `System.err` with the exception class name only. Never log IPs, keys, request bodies, or SDK payloads.

**Python**: 4-space indent, a module docstring stating whether the script touches AWS, constants at module top, `argparse`. Guard with `raise SystemExit("...; no changes made.")` on the wrong account or ambiguous state.

**JS**: 2-space indent, `fetch` with `AbortSignal.timeout`, handle 429 using `retry-after`.

**Docs**: one sentence per line in Markdown.

Comments explain *why*, not *what*. Keep them sparse.

## Boundaries

**Always**
- Work on a branch and go through a PR.
- Run all three check commands before pushing.
- Trust only `requestContext.http.sourceIp` for the client IP. Never `X-Forwarded-For`.
- Preserve the `GET /health` contract and the Lambda handler name.
- Preserve `frontend/package.json`, its lockfile, `npm run build`, and the `dist` output; Cloudflare's deploy depends on them.

**Ask the human first**
- Adding or upgrading a dependency (`pom.xml`, `package.json`).
- Editing anything in `.github/workflows/`, `infrastructure/`, or `AGENTS.md`.
- Changing rate limits, DynamoDB timeouts (`DynamoIpCounter.java`), the CORS origin list, or budget-guard logic.
- Changing any hardcoded AWS ID (account `398074591774`, API `mu0qe823ue`, function `csci201-team3-api`, table `csci201-team3-ip-limits`). These are duplicated across several files and must change together.

**Never**
- IMPORTANT: never push, commit, or merge directly to `main`. A push to `main` deploys to production.
- Force-push, use `--no-verify`, or rewrite shared history.
- Run anything that touches live AWS: `infrastructure/configure-gateway.py` (changes AWS even with no flags), `configure-ip-limits.py`, `bootstrap.sh`, `aws ...` CLI commands, or the deploy workflow. Offline-safe: `budget_stack.py` and the infra unit tests only.
- Commit secrets: `.env*` (except `.env.example`), AWS keys, Supabase service-role keys, passwords, receipt photos. Never put secrets in `VITE_*` vars; they ship to the browser.
- Lower the budget threshold to "test" the budget guard.
- Disable, skip, or delete tests to make CI pass.

## Gotchas

- Cloudflare preview URLs cannot call the API (CORS allows only production and localhost). Test the API connection from `http://localhost:5173`.
- If `IP_RATE_LIMIT_TABLE` is unset in Lambda, every request returns 503.
- DynamoDB client timeouts are tuned for Lambda cold starts. Shrinking them caused a production outage (PR #4, fixed by hotfix #5). Unit tests will not catch this.
- Editing `budget_guard.py` changes nothing live until the owner redeploys the CloudFormation stack.
- Login, permission checks, and per-user quotas must exist before any private data or OCR route goes live.

## Maintaining this file

Changes to `AGENTS.md` go through a PR like any other code.
Add a rule only when an agent has actually made that mistake. Remove rules that no longer apply.
