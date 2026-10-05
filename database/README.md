# Database work

Supabase is the planned Postgres database and optional private receipt storage.
The Java API will authenticate users, enforce group membership, and own writes.
No schema or application authentication has been deployed by this starter.

Plan the tables together before adding the first SQL migration:

- `users`: account identity and a password hash, never plaintext passwords.
- `sessions`: hashed opaque session tokens and expiry.
- `groups` and `group_members`: group ownership and membership.
- `expenses`: payer, group, description, currency, integer amount in cents.
- `expense_shares`: each participant's amount in cents, summing to the expense total.
- Optional receipt metadata linked to an expense and a private storage object.

Keep RLS enabled and anonymous access denied on private tables.
Use atomic database transactions for expense creation and its shares.
Java threads do not replace database transactions across separate Lambda instances.
Keep database credentials only in backend configuration; never in `VITE_*`, the React bundle, or Git.
