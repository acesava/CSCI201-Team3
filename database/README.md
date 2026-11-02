# Database work

Supabase is the planned Postgres database and optional private receipt storage.
The Java API will authenticate users, enforce receipt ownership, and own writes.
No schema or application authentication has been deployed by this starter.

Plan the tables together before adding the first SQL migration:

- `users`: account identity and a password hash, never plaintext passwords.
- `sessions`: hashed opaque session tokens and expiry.
- `receipts`: owner account, date, optional description, currency, integer total in cents, participant count, and saved equal-split result.
- Optional receipt image metadata linked to its owner's receipt and a private storage object.

Participants are a count used by the calculator, not linked user accounts.
Persistent groups, group membership, shared balances, payment records, and settlements are outside the current scope.
Validate that the participant count is a positive whole number and that all displayed shares sum to the receipt total, including rounding adjustments.
Keep receipt totals and their saved split results consistent using atomic writes or transactions when multiple rows are involved.

Keep RLS enabled and anonymous access denied on private tables.
Enforce ownership on every receipt operation.
Java threads do not replace database transactions across separate Lambda instances.
Keep database credentials only in backend configuration; never in `VITE_*`, the React bundle, or Git.

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
