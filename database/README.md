# Database work

Supabase Postgres stores accounts, sessions, participants, expenses, settlements, monthly summaries, and usage quotas.
The schema is in `migrations/001_initial_schema.sql`; run it once in the Supabase SQL Editor for project `klxskdrnvslhqrwmuiuu`.
The Java API is the only client: it authenticates users, enforces ownership, and owns all writes.

Only the account holder has a login.
The people they split with are rows in `participants`, with an optional phone number for SMS reminders.
Every owned table cascades from `users`, so deleting an account deletes its data.
All money is stored as integer cents, and expense writes happen in one transaction so totals, items, shares, and payments stay consistent.

RLS is enabled with no policies, and `anon`/`authenticated` grants are revoked, so the public Supabase API cannot read these tables.
The backend connects as the table owner, which bypasses RLS.
Keep database credentials only in Lambda configuration; never in `VITE_*`, the React bundle, or Git.
Java threads do not replace database transactions across separate Lambda instances; quota counters and settlement confirmation use atomic SQL for that reason.

To test the schema and the Java queries locally, apply the migration to a disposable local Postgres, create empty `anon` and `authenticated` roles first, and run:
`POSTGRES_TEST_URL='jdbc:postgresql://127.0.0.1:55432/postgres?user=postgres' ./backend/mvnw -f backend/pom.xml -Dtest=JdbcApiFlowTest test`.
That test truncates every table, so it refuses to run against anything except localhost.

## Scheduled database health check

The `Supabase database health` GitHub Actions workflow checks database connectivity directly every eight hours, at 00:17, 08:17, and 16:17 UTC.
It makes one request per run, approximately 90 requests per 30-day month, with no retries.
It never calls AWS, reads expense data, or changes application data.
The public `check_database_health()` function only returns the integer `1` and runs with the caller's permissions.
Private-table grants and RLS policies are unchanged.

Setup:

1. In Supabase, confirm the selected project is `csci201-team3` (`klxskdrnvslhqrwmuiuu`), then run `database/health-check.sql` once in the SQL Editor.
   The SQL refuses to replace an existing function of the same name.
2. Copy this project's existing **publishable** key (`sb_publishable_...`) into the GitHub repository Actions secret `SUPABASE_HEALTH_KEY`.
   Do not use a secret key, legacy service-role key, database password, or personal access token.
3. After review and merge to `main`, open **Actions → Supabase database health → Run workflow** and verify the run passes.
   The automatic schedule only runs from the default branch; an open PR does not activate it.

The request has a 15-second timeout, refuses redirects, and accepts only HTTP 200 with JSON integer `1`.
The job fails if the secret is missing, the API is unavailable, or the response is unexpected.
Logs contain a short status message without credentials or response bodies.
To stop the checks, disable this workflow in GitHub Actions.

Supabase says a few daily database requests usually avoid Free-plan inactivity pausing, but this is not an availability guarantee ([policy](https://supabase.com/docs/guides/platform/free-project-pausing)).
GitHub schedules can be delayed or dropped and are automatically disabled in public repositories after 60 days without repository activity ([scheduling limitations](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)).
Check the workflow before a demo and re-enable it in GitHub Actions if necessary.
