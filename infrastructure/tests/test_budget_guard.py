import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from budget_guard import enforce
from budget_stack import template


class BudgetGuardTests(unittest.TestCase):
    def setUp(self):
        self.clients = {name: Mock() for name in ("budgets", "lambda", "apigatewayv2", "sns")}
        self.config = {"ACCOUNT_ID": "123", "BUDGET_NAME": "semester", "LIMIT_USD": "14",
                       "TARGET_FUNCTION": "team-api", "API_ID": "api", "ROUTE_ID": "route",
                       "TRIGGER_TOPIC": "arn:trigger", "ALERT_TOPIC": "arn:alerts"}
        self.event = {"Records": [{"EventSource": "aws:sns", "Sns": {"TopicArn": "arn:trigger"}}]}
        self.spend("14")

    def spend(self, amount, unit="USD"):
        self.clients["budgets"].describe_budget.return_value = {
            "Budget": {"CalculatedSpend": {"ActualSpend": {"Amount": amount, "Unit": unit}}}}

    def test_below_threshold_never_changes_backend(self):
        self.spend("13.99")
        result = enforce(self.event, self.clients, self.config)
        self.assertEqual(result["status"], "below_threshold")
        for service in ("lambda", "apigatewayv2", "sns"):
            self.assertEqual(self.clients[service].mock_calls, [])

    def test_boundary_and_duplicate_delivery_stop_same_resources(self):
        for _ in range(2):
            self.assertEqual(enforce(self.event, self.clients, self.config)["status"], "shutdown")
        self.clients["lambda"].put_function_concurrency.assert_called_with(
            FunctionName="team-api", ReservedConcurrentExecutions=0)
        self.clients["apigatewayv2"].update_route.assert_called_with(
            ApiId="api", RouteId="route", AuthorizationType="AWS_IAM")
        self.assertEqual(self.clients["sns"].publish.call_count, 2)

    def test_forged_or_missing_trigger_does_not_read_billing(self):
        for event in ({}, {"Records": [{"EventSource": "aws:sns", "Sns": {"TopicArn": "arn:other"}}]}):
            with self.assertRaises(ValueError):
                enforce(event, self.clients, self.config)
        self.clients["budgets"].describe_budget.assert_not_called()

    def test_lambda_failure_still_closes_api_and_raises_for_retry(self):
        self.clients["lambda"].put_function_concurrency.side_effect = RuntimeError("denied")
        with self.assertRaises(RuntimeError):
            enforce(self.event, self.clients, self.config)
        self.clients["apigatewayv2"].update_route.assert_called_once()
        self.assertIn("partial_failure", self.clients["sns"].publish.call_args.kwargs["Message"])

    def test_api_failure_still_stops_lambda_and_raises(self):
        self.clients["apigatewayv2"].update_route.side_effect = RuntimeError("denied")
        with self.assertRaises(RuntimeError):
            enforce(self.event, self.clients, self.config)
        self.clients["lambda"].put_function_concurrency.assert_called_once()

    def test_billing_failure_causes_retry_not_false_success(self):
        self.clients["budgets"].describe_budget.side_effect = RuntimeError("unavailable")
        with self.assertRaises(RuntimeError):
            enforce(self.event, self.clients, self.config)
        self.assertEqual(self.clients["lambda"].mock_calls, [])

    def test_invalid_spend_does_not_change_backend(self):
        for amount, unit in (("NaN", "USD"), ("-1", "USD"), ("20", "EUR")):
            self.spend(amount, unit)
            with self.assertRaises(ValueError):
                enforce(self.event, self.clients, self.config)
        self.assertEqual(self.clients["lambda"].mock_calls, [])

    def test_notification_failure_is_not_silently_accepted(self):
        self.clients["sns"].publish.side_effect = RuntimeError("unavailable")
        with self.assertRaises(RuntimeError):
            enforce(self.event, self.clients, self.config)
        self.clients["lambda"].put_function_concurrency.assert_called_once()

    def test_template_has_no_public_guard_and_no_monthly_reset(self):
        resources = template()["Resources"]
        budget = resources["SemesterBudget"]["Properties"]["Budget"]
        self.assertEqual(budget["TimeUnit"], "CUSTOM")
        self.assertTrue(all(v.isdecimal() for v in budget["TimePeriod"].values()))
        self.assertFalse(budget["CostTypes"]["IncludeCredit"])
        self.assertFalse(budget["CostTypes"]["IncludeRefund"])
        self.assertEqual(budget["BudgetLimit"]["Amount"], 14)
        self.assertEqual(resources["InvokeGuard"]["Properties"]["Principal"], "sns.amazonaws.com")
        for statement in resources["GuardRole"]["Properties"]["Policies"][0]["PolicyDocument"]["Statement"]:
            self.assertNotEqual(statement["Resource"], "*")
        self.assertEqual(sum(r["Type"] == "AWS::CloudWatch::Alarm" for r in resources.values()), 6)
        statements = resources["TopicPolicies"]["Properties"]["PolicyDocument"]["Statement"]
        self.assertEqual(len({s["Sid"] for s in statements}), len(statements))


if __name__ == "__main__":
    unittest.main()
