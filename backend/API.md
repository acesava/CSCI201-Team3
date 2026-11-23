# Backend API

All routes go through `edu.usc.csci201.team3.Handler::handleRequest` behind API Gateway's `$default` route.
Requests and responses are JSON.
Money is always an integer number of cents (`2150` means $21.50), except in the monthly summary's `stats`, which uses exact decimal strings such as `"15.00"`.
Errors look like `{"error": "message safe to show the user"}`.

## Model

Only the account holder logs in.
The people they split with are **participants**: a name plus an optional phone number for text messages.
Participants never need an account.
Each account has one participant with `isYou: true` that represents the account holder.

## Authentication

Send `Authorization: Bearer <token>` on every route except `/health`, `/auth/register`, and `/auth/login`.
Tokens last 7 days; a `401` means the user must log in again.

| Method | Path | Body | Result |
| --- | --- | --- | --- |
| POST | `/auth/register` | `email`, `password` (8 to 128 chars), `displayName` | `201` `{token, expiresAt, user}`; `409` if the email exists |
| POST | `/auth/login` | `email`, `password` | `{token, expiresAt, user}`; `401` on a wrong email or password |
| POST | `/auth/logout` | none | `204` |
| GET | `/me` | none | user with `preferences` |
| PATCH | `/me` | optional `displayName`, `preferences.{expenseNotifications, monthlySummaryEmail, currency}` | updated user |
| POST | `/me/password` | `currentPassword`, `newPassword` | new `{token, ...}`; all older sessions end |
| DELETE | `/me` | `password` | `204`; deletes the account and all of its data |

## Participants and text messages

| Method | Path | Body | Result |
| --- | --- | --- | --- |
| GET | `/participants?includeArchived=true` | none | `{participants: [...]}` |
| POST | `/participants` | `displayName`, optional `phone` | `201` participant |
| PATCH | `/participants/{id}` | optional `displayName`, `phone` (`null` clears it), `archived` | participant |
| DELETE | `/participants/{id}` | none | `{deleted: true}`, or `{archived: true}` if they have history |
| POST | `/participants/{id}/remind` | none | texts what they currently owe you: `{notifications: [...]}` |

Phone numbers are stored in E.164 form; a 10-digit US number such as `(213) 555-0100` is accepted and becomes `+12135550100`.
Each entry in `notifications` has `participantId`, `name`, and `status` (`sent`, `skipped`, or `failed`) with an optional `reason`.
Each account can send at most 25 texts per UTC day.
Only add phone numbers of people who agreed to receive texts; every message ends with "Reply STOP to opt out."

## Expenses

| Method | Path | Body | Result |
| --- | --- | --- | --- |
| POST | `/expenses` | see below; add `"notify": true` to text each participant their share | `201` expense |
| GET | `/expenses?month=YYYY-MM` | none | `{expenses: [...]}` newest first, each with `yourShareCents` |
| GET | `/expenses/{id}` | none | expense with `items` |
| PUT | `/expenses/{id}` | same as create | expense |
| DELETE | `/expenses/{id}` | none | `204` |
| POST | `/expenses/{id}/notify` | none | `{notifications: [...]}` |
| POST | `/splits/preview` | split fields only | `{totalCents, shares}` without saving |

Expense body:

```json
{
  "description": "Bella's Cafe",
  "category": "Dining",
  "date": "2026-10-10",
  "notes": "optional",
  "entryMethod": "manual | receipt",
  "splitMethod": "equal | custom | itemized",
  "participants": ["<participant id>", "..."],
  "totalCents": 2150,
  "customShares": [{"participantId": "...", "amountCents": 717}],
  "items": [{"name": "Latte", "quantity": 1, "totalCents": 500, "participantIds": ["..."]}],
  "taxCents": 0,
  "tipCents": 0,
  "paidBy": "<participant id, defaults to you>"
}
```

- `equal` needs `totalCents`; leftover cents go to the first participants listed, so $21.50 / 3 is $7.17, $7.17, $7.16.
- `custom` needs `totalCents` and `customShares` that add up to it exactly.
- `itemized` splits each item among its `participantIds` and splits tax plus tip equally among all `participants`; `totalCents` is optional and, if sent, must match.
- Instead of `paidBy`, send `payments: [{participantId, amountCents}]` when several people paid; they must add up to the total.

## Balances and payments

| Method | Path | Body | Result |
| --- | --- | --- | --- |
| GET | `/balances` | none | `youOweCents`, `owedToYouCents`, per-participant `netCents`, `suggestedTransfers`, `recentSettlements` |
| GET | `/settlements` | none | `{settlements: [...]}` |
| POST | `/settlements` | `amountCents`, optional `fromParticipantId` and `toParticipantId` (each defaults to you), `date`, `method`, `note` | `201` settlement |
| POST | `/settlements/{id}/confirm` | none | settlement; `409` if it is no longer pending |
| POST | `/settlements/{id}/reject` | none | settlement |
| DELETE | `/settlements/{id}` | none | `204` |

Net balance = expenses paid - assigned shares + confirmed payments sent - confirmed payments received.
Positive means the participant is owed money.
A payment to you is confirmed immediately; a payment between two other participants stays `pending` until you confirm it.
The app never moves money; these are records of payments made with Venmo, Zelle, or cash.

## Receipts and AI summary

| Method | Path | Body | Result |
| --- | --- | --- | --- |
| POST | `/receipts/scan` | `imageBase64` (JPEG or PNG, data URL allowed, 4 MB max) | draft: `merchant`, `totalCents`, `subtotalCents`, `taxCents`, `tipCents`, `date`, `category`, `items` |
| GET | `/summaries/monthly?month=YYYY-MM` | none | `available`, `summary`, `stats`, `comparisonAvailable`, or a `message` explaining why it is unavailable |

A scan returns a draft for the user to review; nothing is saved until they create the expense.
A `422` means the receipt was unreadable; a `503` means scanning is down; show manual entry in both cases.
Each account can scan 20 receipts and generate 10 new summaries per UTC day; an unchanged month reuses the stored summary.
Only aggregated statistics are sent to Gemini, never names, descriptions, or phone numbers.

## Lambda configuration

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://<supabase pooler host>:6543/postgres?sslmode=require` |
| `DATABASE_USER`, `DATABASE_PASSWORD` | Supabase database credentials (the table owner role) |
| `GEMINI_API_KEY`, optional `GEMINI_MODEL` | AI summaries; default model `gemini-2.5-flash` |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER` | Text messages |
| `TESSDATA_PREFIX` | Folder containing `eng.traineddata`; defaults to `/opt/tessdata` |

Missing database settings return `503` on every route except `/health`.
Missing Gemini or Twilio settings only make summaries or texts report "unavailable".
Twilio trial accounts can only text phone numbers verified in the Twilio console.
Receipt scanning on Lambda also needs `libtesseract` and `leptonica` from a Lambda layer, plus more memory and a longer timeout than the starter's settings.
