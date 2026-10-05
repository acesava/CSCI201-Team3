# Budget protection

This setup is an owner-controlled safety switch for the team backend.
It does not upgrade the AWS account or require access keys.
It is infrastructure code; the application backend remains Java.

## Threshold and scope

The cumulative account budget is $14 from October 1 through December 31, 2026 (UTC).
At setup on October 4, reported usage was $0, so $14 represents 10% of the verified $140 credit balance.
It does not reset each month.
Credits and refunds are excluded so credits cannot hide metered costs behind a $0 payable bill.
Free allowances and applicable discounts still affect reported usage.
This is a cost proxy, not a direct measurement of promotional credit deductions.

- Email budget warnings when reported usage crosses $7, $11.20, and $14.
- The $14 notification invokes a private safety function through SNS.
- The function independently reads the budget, sets `csci201-team3-api` reserved concurrency to zero, and changes the existing API Gateway default route to `AWS_IAM`.
- Both stop operations are attempted independently, with failures retried and reported.
- The existing $1 monthly early-warning budget is retained.
- Restart is manual; routine Java deployments cannot restore concurrency or public access.

**This is not a hard spending cap.**
AWS Budgets normally updates several times per day, so usage can overshoot before a notification arrives.
Already-running requests can finish.
Gateway requests, stored data, and unrelated services can still incur costs after the Java backend stops.
The frontend and Supabase remain available.
Protection must be reviewed before January 1, 2027, when this semester budget ends.

## Operational alerts

Six standard CloudWatch alarms send email through a separate SNS topic.
They never trigger automatic shutdown:

| Alert | Threshold |
| --- | --- |
| Traffic | At least 180 API requests/minute in 3 of 5 minutes |
| Rejections | At least 30 API 4xx responses/minute in 2 of 3 minutes |
| Backend failures | At least 5 API 5xx responses/minute in 2 of 3 minutes |
| Slow responses | Average API latency at least 1 second in 2 of 3 minutes |
| Concurrency | At least 8 concurrent Java invocations in 2 of 3 minutes |
| Safety switch failure | At least one safety-function error in a minute |

Missing data does not trigger an alarm.
These are warning thresholds, not proof of abuse; shared campus traffic and tests can trigger them.
The SNS email subscription must be confirmed before operational alerts can arrive.
Direct AWS Budgets email notifications do not depend on that subscription.
Six standard alarm metrics fit within AWS's current allowance of ten, shared with any other alarms in the account.
SNS, Lambda and logs have their own usage allowances; this is not a guarantee of zero metered usage.

## Deploy and verify (owner only)

Render the template without AWS access:

```bash
python3 infrastructure/budget_stack.py > /tmp/team-budget-stack.json
python3 -m unittest discover -s infrastructure/tests -v
```

Use authenticated AWS CloudShell in `us-west-2` after reviewing the template.
Verify account `398074591774` before creating the stack.
Pass the owner's existing budget email as the `AlertEmailAddress` parameter; do not commit a private email or credentials.
The stack name is `csci201-team3-budget-safety` and requires `CAPABILITY_NAMED_IAM`.
The guard role can read only the named budget, modify concurrency only on the team Java function, patch only its existing default route, publish only to the alert topic, and write only its own logs.
It cannot change account plans, payment methods, other functions, or GitHub deployment permissions.

After deployment, verify stack completion, budget thresholds, SNS subscriptions and six enabled alarm actions.
Invoke the guard with a matching SNS event at current spend below $14 to verify its deployed role can read the budget without stopping the application.
Do not reduce the live threshold merely to test it.
Unit tests cover threshold crossing, duplicates, forged events and partial failures.
A real shutdown requires a separate controlled exercise or the actual threshold event.

## Manual recovery

First investigate traffic and costs, fix the cause, and deliberately revise the budget if more usage is authorized.
Otherwise a later budget notification may shut it down again.
Then restore the original settings:

```bash
aws lambda delete-function-concurrency --region us-west-2 --function-name csci201-team3-api
aws apigatewayv2 update-route --region us-west-2 --api-id mu0qe823ue --route-id 27yeb25 --authorization-type NONE
```

These commands restore public access and must be an intentional owner action.
Check `/health` and the live frontend afterward.
Never disable the safety switch by changing the Java deployment role or exposing the old Lambda Function URL.

References: [AWS Budgets timing](https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-managing-costs.html), [Lambda concurrency](https://docs.aws.amazon.com/lambda/latest/dg/configuration-concurrency.html), [CloudWatch pricing](https://aws.amazon.com/cloudwatch/pricing/).
