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
