"""AWS Budgets SNS safety switch. This is infrastructure, not application logic."""
import json
import os
from decimal import Decimal


def enforce(event, clients, config):
    records = event.get("Records", [])
    if not records or any(
        record.get("EventSource") != "aws:sns"
        or record.get("Sns", {}).get("TopicArn") != config["TRIGGER_TOPIC"]
        for record in records
    ):
        raise ValueError("Only the configured budget SNS topic may trigger this function")

    budget = clients["budgets"].describe_budget(
        AccountId=config["ACCOUNT_ID"], BudgetName=config["BUDGET_NAME"]
    )["Budget"]
    spend = budget["CalculatedSpend"]["ActualSpend"]
    amount = Decimal(spend["Amount"])
    if spend["Unit"] != "USD" or not amount.is_finite() or amount < 0:
        raise ValueError("Unexpected budget spend; review budget configuration")
    threshold = Decimal(config["LIMIT_USD"])
    if amount < threshold:
        return {"status": "below_threshold", "reported_usd": str(amount)}

    # Attempt both controls even if one fails. Raising afterward activates retries
    # and the guard-error alarm. Repeating these operations is safe.
    failures = []
    try:
        clients["lambda"].put_function_concurrency(
            FunctionName=config["TARGET_FUNCTION"], ReservedConcurrentExecutions=0
        )
    except Exception as error:
        failures.append("Lambda: " + type(error).__name__)
    try:
        clients["apigatewayv2"].update_route(
            ApiId=config["API_ID"], RouteId=config["ROUTE_ID"],
            AuthorizationType="AWS_IAM",
        )
    except Exception as error:
        failures.append("API route: " + type(error).__name__)

    result = {"status": "partial_failure" if failures else "shutdown",
              "reported_usd": str(amount), "threshold_usd": str(threshold),
              "failures": failures,
              "notice": "Backend restart requires owner action. Billing is delayed; costs may exceed the threshold."}
    clients["sns"].publish(
        TopicArn=config["ALERT_TOPIC"], Subject="Team 3 AWS budget safety switch",
        Message=json.dumps(result),
    )
    if failures:
        raise RuntimeError("; ".join(failures))
    return result


def handler(event, context):
    import boto3
    region = os.environ["AWS_REGION"]
    clients = {name: boto3.client(name, region_name="us-east-1" if name == "budgets" else region)
               for name in ("budgets", "lambda", "apigatewayv2", "sns")}
    result = enforce(event, clients, os.environ)
    print(json.dumps(result))
    return result
