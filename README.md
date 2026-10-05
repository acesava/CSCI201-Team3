# CSCI 201 Team 3: group expense tracker

React frontend, Java 21 AWS Lambda backend, and Supabase Postgres.
This repository is a starter, not the completed course project.
There is no Spring Boot dependency.

- [Live starter](https://csci201-expense-tracker.pages.dev)
- [Java health endpoint](https://sautin26evkxdm33hseqsah6te0kjgua.lambda-url.us-west-2.on.aws/health)
- [Build and deployment runs](https://github.com/acesava/CSCI201-Team3/actions)

## How our app works

Cloudflare delivers the React website to the user's browser.
React sends requests to Java and displays the results Java sends back.

```mermaid
flowchart TD
    CF["Cloudflare Pages<br/>Hosts our website"] -->|Loads the website| FE["User's browser<br/>React screens and forms"]
    FE -->|HTTPS request| API["AWS Lambda<br/>Our Java backend"]
    API -->|JSON response| FE
    API -.->|Read / save data| DB[("Supabase Postgres<br/>Accounts and groups<br/>Expenses and shares")]
    API -.->|Read receipt| OCR["Tesseract OCR<br/>Java worker threads<br/>Inside Lambda"]
    OCR -.->|Items and prices| API
```

**Working now:** the website and Java health check.
**Still to build:** Java login, permissions, expense splitting, database integration, and receipt reading.
Dashed arrows show planned connections.
Users will also be able to enter expenses manually without uploading a receipt.

## How our changes go live

```mermaid
flowchart TD
    TEAM["Team member<br/>Code on a branch<br/>Open a pull request"] --> MAIN["Review and merge<br/>into main"]
    MAIN --> CFBUILD["Cloudflare<br/>Automatically builds React"]
    CFBUILD --> SITE["Updated live website"]
    MAIN -->|Backend changes| ACTIONS["GitHub Actions<br/>Tests and packages Java"]
    ACTIONS --> LAMBDA["Updates our Lambda<br/>Checks its health"]
```

No manual deployment button is needed for normal frontend or backend changes merged into `main`.
The separate **Build and test** workflow checks pull requests before merging.
Supabase database changes are not automatically deployed yet.

## Start locally

Install Node.js 22 and a JDK 21.
Maven is downloaded automatically by the checked-in wrapper.
Clone `https://github.com/acesava/CSCI201-Team3.git`, then run these commands from its folder:

```sh
cd frontend
npm ci
cp .env.example .env.local
# Windows PowerShell: Copy-Item .env.example .env.local
npm run dev
```

Open **http://localhost:5173** and click **Check backend**.
The example environment file contains our public Java API URL, not a secret.
Use `localhost`, not `127.0.0.1`: Lambda's current CORS settings allow the former on port 5173.
If port 5173 is busy, stop your previous dev server; Vite will report the conflict instead of silently picking an unsupported port.

In another terminal, starting from the repository root, test and package Java:

```sh
cd backend
./mvnw verify
# Windows: .\mvnw.cmd verify
```

The deployment artifact is `backend/target/expense-api.jar`.
The Lambda handler is `edu.usc.csci201.team3.Handler::handleRequest`.
`GET /health` is the only implemented route.
All other routes return 404, and non-GET requests to `/health` return 405.

The Java backend is a Lambda handler, not a local HTTP server.
These Maven commands run tests and create the deployment package; the frontend connects to the shared deployed API.
Restart Vite after changing `VITE_API_BASE_URL` in `frontend/.env.local`.
The page's connection check uses a real HTTP request; without a configured URL it reports that setup is incomplete.

## Folder responsibilities

| Folder | What belongs here |
| --- | --- |
| `frontend/` | React screens, forms, charts, and calls to Java |
| `backend/` | Java login, permissions, groups, expenses, and splitting logic |
| `database/` | Reviewed SQL migrations and database notes |
| `infrastructure/` | Cloud setup and deployment instructions |
| `.github/workflows/` | Build checks and Java deployment |

See [cloud setup](infrastructure/SETUP.md) for exact service settings and remaining owner actions.

## Work together

Use a feature branch and a pull request for each change.
The build checks compile React and run the Java tests.
`main` is our production branch; there is no separate branch named `production`.
Cloudflare deploys successful frontend builds from `main` automatically.
GitHub Actions deploys Java when `backend/` or its deployment workflow changes on `main`.
Pushing a feature branch does not update the production Java backend.
Check the pull request's frontend, backend, and Cloudflare results before merging.
These checks are not currently enforced by branch protection, and Cloudflare does not wait for the separate GitHub checks on a direct push to `main`.
Cloudflare preview URLs can display the frontend, but the production API currently permits only the production website and configured local origins.
Use the local URL above for the connection check until preview-origin support is deliberately configured.
Do not commit passwords, AWS access keys, session tokens, receipt photos, or Supabase service-role keys.
The repository is public; teammates must accept their collaborator invitation to push changes.
You can replace the starter UI, but preserve `frontend/package.json`, its lockfile, `npm run build`, and the `dist` output unless the deployment settings are updated too.
Java changes must preserve the configured handler and `GET /health` contract, or update the deployment configuration and checks together.

## Next implementation milestones

- Register/login/logout in Java, password hashing, expiring sessions, and guest sample data.
- Create groups and check membership on every private operation.
- Manual expense entry with a payer, participants, and integer-cent splits.
- Store each expense and its shares in a transaction, then show balances.
- Add editable receipt extraction only after the manual path works.
- Demonstrate explicit Java concurrency with bounded receipt tasks and collect all results before a Lambda invocation returns.

Tesseract, user authentication, database access, and multithreading are not implemented in this starter.
Concurrent Lambda invocations alone are not the planned Java multithreading demonstration.
