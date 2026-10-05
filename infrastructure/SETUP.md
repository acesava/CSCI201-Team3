# Team cloud setup

## Architecture

```text
Browser: React, served by Cloudflare Pages
  -> HTTPS request to Java Lambda
     -> login / permissions / expense logic
     -> Supabase Postgres and optional private receipt storage

GitHub main -> Cloudflare build -> frontend deployment
GitHub main -> Actions build/test -> short-lived AWS role -> Java deployment
```

The browser will not receive database credentials or a Supabase service-role key.
Login and expense features still need implementation.

## Verified accounts on October 4, 2026

- GitHub: `acesava/csci201-expense-tracker`; Ace has write access.
- AWS: `CSCI201 Team 3`, account `398074591774`.
  The console displayed Free plan, $100 credit remaining, and April 4, 2027 expiration.
  This observation is not a guarantee of future eligibility or unlimited usage.
- Supabase: free organization `CSCI201 Team 3`, project `csci201-team3` in Oregon (`us-west-2`).
- Cloudflare: `acesavage344@gmail.com` account, with the existing QualCare worker left unchanged.

Use `us-west-2` for Lambda so the backend and database are in the same region.

## AWS bootstrap

`bootstrap.sh` creates:

- A Java 21 ARM64 Lambda named `csci201-team3-api`, 256 MB memory and 10-second timeout.
- A log group with 7-day retention and an execution role restricted to writing those logs.
- A public function URL for the starter's health endpoint.
- GitHub OIDC trust restricted to `acesava/csci201-expense-tracker`, branch `main`.
- A deployment role allowed only to update this function's code, read its configuration, and invoke it for a health test.

The deployment role cannot manage IAM, unrelated functions, or database credentials.
Code deployed by that role executes with the function's runtime permissions and environment, so protect `main` and review changes before merging.
No long-lived AWS access key is needed.

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

Use the private `acesava/csci201-expense-tracker` repository.
Authorize the Cloudflare Workers and Pages GitHub app for only this repository.
Kyle's repository and the old Spring Boot starter remain unchanged.

Once the repository is available in Cloudflare:

| Setting | Value |
| --- | --- |
| Product | Pages, Git integration |
| Project name | `csci201-team3` (or an available equivalent) |
| Production branch | `main` |
| Root directory | `frontend` |
| Framework | React / Vite |
| Build command | `npm run build` |
| Build output | `dist` |
| `NODE_VERSION` | `22` |
| `VITE_API_BASE_URL` | Function URL returned by AWS bootstrap |

Set Lambda Function URL CORS to the exact resulting Pages origin.
For the initial health route, allow `GET` only.
Add localhost or preview origins explicitly when needed; CORS does not replace authentication.
Rebuild the frontend after changing its `VITE_API_BASE_URL` because Vite embeds the URL during the build.

## Enable Java redeployments

After bootstrap, set this GitHub Actions repository variable:

```text
AWS_DEPLOY_ROLE_ARN=arn:aws:iam::398074591774:role/csci201-team3-github-deploy
```

The workflow remains disabled while that variable is absent.
It deploys only from `main`, after Java tests pass.
Use Actions -> Deploy Java backend -> Run workflow on `main` for the first run or a redeploy.
Every deployment checks `/health` through the Lambda invocation API.
The browser connection check separately validates the public URL and CORS.

## Supabase

The database was created in the existing Supabase account.
The creation form had automatic RLS enabled and automatic exposure of new tables disabled.
Keep the database password in a password manager.
Choose a backend-only database access method before adding credentials to Lambda; the starter currently requires none.
See `database/README.md` for the proposed data model.

## Cost controls and limits

Keep the selected free plans and check their dashboards before demos.
AWS credits and Free-plan access expire; the observed expiration is April 4, 2027 or earlier if credits are exhausted.
Set a low AWS cost alert; alerts do not stop requests or spending.
Keep provisioned concurrency off and avoid a VPC/NAT gateway for this starter.
This repository is private: GitHub Actions consumes the owner's included private-repository minutes and storage allowance.
Keep workflows on standard Linux runners and check the account's Actions budget settings.
Cloudflare Pages' Free build allowance and Supabase's database/storage/egress quotas still apply.
OCR will require a separate measured packaging and cost check before enabling uploads.

Sources: [AWS Java](https://docs.aws.amazon.com/lambda/latest/dg/lambda-java.html), [function URL permissions](https://docs.aws.amazon.com/lambda/latest/dg/urls-auth.html), [Cloudflare Git integration](https://developers.cloudflare.com/pages/configuration/git-integration/).
