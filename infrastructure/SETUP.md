# Team cloud setup

See [budget protection](BUDGET-PROTECTION.md) for the $14 semester safety switch, alert thresholds, deployment verification, and owner recovery procedure.

## Architecture

```text
Browser: React, served by Cloudflare Pages
  -> HTTPS request to API Gateway (5 requests/sec, burst 10)
     -> Java Lambda
     -> login / permissions / expense logic
     -> Supabase Postgres and optional private receipt storage

GitHub main -> Cloudflare build -> frontend deployment
GitHub main -> Actions build/test -> short-lived AWS role -> Java deployment
```

The browser will not receive database credentials or a Supabase service-role key.
Login and expense features still need implementation.

## Verified accounts on October 4, 2026

- GitHub: `acesava/CSCI201-Team3`; Ace has write access.
- AWS: `CSCI201 Team 3`, account `398074591774`.
  The console displayed Free plan, $140 credit remaining, and April 4, 2027 expiration.
  This observation is not a guarantee of future eligibility or unlimited usage.
- Supabase: free organization `CSCI201 Team 3`, project `csci201-team3` in Oregon (`us-west-2`).
- Cloudflare: `acesavage344@gmail.com` account, with the existing QualCare worker left unchanged.

Use `us-west-2` for Lambda so the backend and database are in the same region.

## Provisioned starter

- Frontend: https://csci201-expense-tracker.pages.dev
- Java API: https://mu0qe823ue.execute-api.us-west-2.amazonaws.com
- Supabase dashboard: https://supabase.com/dashboard/project/klxskdrnvslhqrwmuiuu
- Cloudflare builds `main`, root `frontend`, command `npm run build`, output `dist`, Node.js 22.
- `VITE_API_BASE_URL` is set in Cloudflare's build environment.
- API Gateway CORS permits the production Pages origin, `http://localhost:5173`, and `http://127.0.0.1:5178`.
- Allowed CORS methods are GET, POST, PUT, PATCH, DELETE, and OPTIONS; only GET /health is implemented.
- GitHub's `AWS_DEPLOY_ROLE_ARN` and `API_BASE_URL` variables are configured.

Supabase is provisioned but not connected to application business logic yet.
There are no application tables, login routes, receipt uploads, or database credentials in the starter.
The live health check verifies browser -> Java connectivity only.

## AWS bootstrap

`bootstrap.sh` creates:

- A Java 21 ARM64 Lambda named `csci201-team3-api`, 256 MB memory and 10-second timeout.
- A log group with 7-day retention and an execution role restricted to writing those logs and updating the IP counter table.
- A public HTTP API Gateway with a default route to Lambda and shared throttling.
- A gateway permission scoped to this API/account and IAM-only access on any legacy Function URL.
- GitHub OIDC trust restricted to `acesava/CSCI201-Team3`, branch `main`.
- A deployment role allowed only to update this function's code, read its configuration, and invoke it for a health test.

The deployment role cannot manage IAM, unrelated functions, or database credentials.
Code deployed by that role executes with the function's runtime permissions and environment, so protect `main` and review changes before merging.
No long-lived AWS access key is needed.
This repository uses GitHub's immutable OIDC subject prefix, `repo:acesava@287342120/CSCI201-Team3@1405003751`.
The trust policy includes this exact prefix plus `:ref:refs/heads/main`; copying a legacy name-only subject will fail.

Review these permissions and public exposure before running bootstrap in CloudShell:

```sh
cd backend
./mvnw verify
cd ..
bash infrastructure/bootstrap.sh
```

Bootstrap verifies the AWS account ID before making changes.
It intentionally does not upgrade the account, create an EC2 server, provision an RDS database, or package OCR.
The public endpoint has no application data in this starter.
Add application authentication before implementing private routes.
Lambda timeout and memory limits bound individual requests; they are not a monthly spending cap.

## Cloudflare Pages: repository authorization

Use the public `acesava/CSCI201-Team3` repository.
Authorize the Cloudflare Workers and Pages GitHub app for only this repository.
Kyle's repository and the old Spring Boot starter remain unchanged.

Once the repository is available in Cloudflare:

| Setting | Value |
| --- | --- |
| Product | Pages, Git integration |
| Project name | `csci201-expense-tracker` |
| Production branch | `main` |
| Root directory | `frontend` |
| Framework | React / Vite |
| Build command | `npm run build` |
| Build output | `dist` |
| `NODE_VERSION` | `22` |
| `VITE_API_BASE_URL` | API Gateway URL returned by AWS bootstrap |

Set API Gateway CORS to the exact resulting Pages origin.
Add localhost or preview origins explicitly when needed; CORS does not replace authentication.
Rebuild the frontend after changing its `VITE_API_BASE_URL` because Vite embeds the URL during the build.

## Enable Java redeployments

After bootstrap, set these GitHub Actions repository variables:

```text
AWS_DEPLOY_ROLE_ARN=arn:aws:iam::398074591774:role/csci201-team3-github-deploy
API_BASE_URL=https://mu0qe823ue.execute-api.us-west-2.amazonaws.com
```

The workflow remains disabled while the deployment role variable is absent.
The public smoke check requires `API_BASE_URL`.
It deploys only from `main`, after Java tests pass.
Use Actions -> Deploy Java backend -> Run workflow on `main` for the first run or a redeploy.
Every deployment checks `/health` through the Lambda invocation API.
The workflow also checks the public gateway URL and CORS.
The browser connection check exercises the same route.

## API throttling

API Gateway HTTP API `csci201-team3-public-api` (`mu0qe823ue`) uses the `$default` stage with automatic deployment.
Default route settings target 5 requests per second and burst capacity 10.
In AWS, select us-west-2, open API Gateway, select this API, then Stages and `$default` to inspect throttling.
Detailed metrics and provisioned concurrency are not enabled.

For repeatable configuration in AWS CloudShell, run:

```sh
python3 infrastructure/configure-gateway.py
# After migrating and testing every client:
python3 infrastructure/configure-gateway.py --close-direct-url
```

The second command changes any legacy Function URL to `AWS_IAM` and removes its two old anonymous invocation permissions.
API Gateway invokes the function using a permission restricted to this API and account.
The public gateway needs no AWS credentials from app users.
The GitHub deployment role remains scoped to the existing function and does not administer API Gateway.
Bootstrap invokes this configuration with the direct URL closed by default.

Update Cloudflare's Production and Preview `VITE_API_BASE_URL`, GitHub's `API_BASE_URL`, and local `.env.local` files whenever the gateway URL changes.
Rebuild Cloudflare and restart local Vite before disabling an old URL.
CORS is a browser rule, not an abuse control or login system.
Handle `429` with bounded retries and a delay; a tight retry loop makes throttling worse.

This is a shared best-effort throttle, not a per-user quota, spending cap, or guarantee against downtime.
An attacker can consume the shared allowance and gateway traffic can still consume credits.
API Gateway is a metered service; the AWS Free plan was retained without a paid upgrade.
Authentication, group permissions, and per-user OCR quotas still need implementation before private or expensive features go live.
See [HTTP API throttling](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-throttling.html) and [API Gateway pricing](https://aws.amazon.com/api-gateway/pricing/).

## Per-IP burst protection

Before deploying the Java guard, review and run in AWS CloudShell:

```sh
python3 infrastructure/configure-ip-limits.py
python3 infrastructure/configure-ip-limits.py --apply
```

This creates `csci201-team3-ip-limits` in us-west-2 with 25 provisioned RCUs and WCUs, Standard storage, TTL cleanup, and no autoscaling.
These capacities fit DynamoDB's published monthly free allowance when it is not consumed by other resources.
The script refuses to proceed if another provisioned table already shares that capacity allowance in this region.
This is not a guarantee that all AWS costs are zero; Lambda, gateway traffic, logs, and storage beyond allowances remain metered.
No paid plan upgrade, on-demand capacity, backups, streams, or global replicas are enabled.

The Lambda execution role gains only `dynamodb:UpdateItem` on this table, alongside its existing log permission.
The script preserves other Lambda environment variables and sets `IP_RATE_LIMIT_TABLE`.
Java fails closed with `503` if the table setting is missing or its counter cannot be checked.
GitHub's deployment role and teammate access do not change.

Each trusted API Gateway source IP gets 30 admissions per fixed 10-second window across all Lambda instances and paths.
A conditional DynamoDB update atomically checks and increments the counter; it never reads a count and then writes it in separate requests.
The key contains a SHA-256 IP hash and window number; hashes are pseudonymous, not anonymized.
Raw IPs are not added to logs or stored in this table.
Rows become eligible for TTL deletion two minutes after their window ends; AWS can take days to physically delete them.
Window expiration depends on the key, not TTL deletion timing.

The response is `429` with `Retry-After` until the next window, or `503` on counter failures/capacity exhaustion.
This limit runs inside Lambda and therefore does not remove invocation costs.
Shared campus IPs share the allowance; distributed or rotating-IP attacks can evade per-IP limits.
Login, group permissions, and account-level OCR quotas are still the application team's work.
Avoid changing the trusted source to `X-Forwarded-For` or other caller-controlled headers.
The IAM-authorized deployment smoke test uses documentation-only IP `192.0.2.1`; public users cannot supply API Gateway's request context.

For rollback, redeploy the previous Java artifact while preserving the existing gateway throttle.
Do not remove the table or IAM policy while limiter code is running.
See [DynamoDB pricing](https://aws.amazon.com/dynamodb/pricing/) for the shared free capacity allowance.

The normal Maven suite checks routing, blocked requests, trusted source IP handling, and window resets without AWS credentials.
To additionally test atomic updates under concurrency, start [DynamoDB Local](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.DownloadingAndRunning.html) and run:

```sh
DYNAMODB_LOCAL_ENDPOINT=http://127.0.0.1:18080 ./backend/mvnw -f backend/pom.xml verify
```

The integration test only accepts a localhost endpoint, uses dummy credentials, creates a disposable table, and removes it afterward.
It verifies that exactly 30 of 60 concurrent attempts succeed against a shared counter.

## Supabase

The database was created in the existing Supabase account.
The creation form had automatic RLS enabled and automatic exposure of new tables disabled.
Keep the database password in a password manager.
Choose a backend-only database access method before adding credentials to Lambda; the starter currently requires none.
See `database/README.md` for the proposed data model.

## Cost controls and limits

Keep the selected free plans and check their dashboards before demos.
AWS credits and Free-plan access expire; the observed expiration is April 4, 2027 or earlier if credits are exhausted.
A $1 monthly AWS cost budget is configured, with email alerts above 50% and 100%.
It excludes credits and refunds so credit-funded usage is still visible.
Alerts do not stop requests or spending.
Keep provisioned concurrency off and avoid a VPC/NAT gateway for this starter.
Standard GitHub-hosted runners are free for this public repository under current GitHub pricing.
Use standard Linux runners; larger runners are billed separately.
Cloudflare Pages' Free build allowance and Supabase's database/storage/egress quotas still apply.
OCR will require a separate measured packaging and cost check before enabling uploads.

Sources: [AWS Java](https://docs.aws.amazon.com/lambda/latest/dg/lambda-java.html), [function URL permissions](https://docs.aws.amazon.com/lambda/latest/dg/urls-auth.html), [Cloudflare Git integration](https://developers.cloudflare.com/pages/configuration/git-integration/).
