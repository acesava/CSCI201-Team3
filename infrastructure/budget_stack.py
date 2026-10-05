"""Render a reviewable CloudFormation template; does not access or modify AWS."""
import json
from pathlib import Path

ACCOUNT = "398074591774"
REGION = "us-west-2"
PREFIX = "csci201-team3"
BUDGET = PREFIX + "-semester-14usd"
GUARD = PREFIX + "-budget-guard"
API = "mu0qe823ue"
ROUTE = "27yeb25"
TARGET = PREFIX + "-api"


def ref(name):
    return {"Ref": name}


def arn(service, resource, region=REGION):
    return f"arn:aws:{service}:{region}:{ACCOUNT}:{resource}"


def allow(actions, resource):
    return {"Effect": "Allow", "Action": actions, "Resource": resource}


def template():
    resources = {}

    def add(name, kind, props, **extras):
        resources[name] = {"Type": "AWS::" + kind, "Properties": props, **extras}

    add("Alerts", "SNS::Topic", {"TopicName": PREFIX + "-alerts"})
    add("Trigger", "SNS::Topic", {"TopicName": PREFIX + "-budget-stop"})
    add("AlertEmail", "SNS::Subscription", {
        "TopicArn": ref("Alerts"), "Protocol": "email", "Endpoint": ref("AlertEmailAddress")})
    budget_arn = arn("budgets", "budget/" + BUDGET, "")
    add("TopicPolicies", "SNS::TopicPolicy", {
        "Topics": [ref("Alerts"), ref("Trigger")],
        "PolicyDocument": {"Version": "2012-10-17", "Statement": [
            {**allow("sns:Publish", ref("Trigger")),
             "Sid": "BudgetMayTriggerShutdown",
             "Principal": {"Service": "budgets.amazonaws.com"},
             "Condition": {"StringEquals": {"aws:SourceAccount": ACCOUNT},
                           "ArnEquals": {"aws:SourceArn": budget_arn}}},
            {**allow("sns:Publish", ref("Alerts")),
             "Sid": "CloudWatchMaySendAlerts",
             "Principal": {"Service": "cloudwatch.amazonaws.com"},
             "Condition": {"StringEquals": {"aws:SourceAccount": ACCOUNT},
                           "ArnLike": {"aws:SourceArn": arn("cloudwatch", "alarm:" + PREFIX + "-*")}}},
        ]}})
    add("GuardLog", "Logs::LogGroup", {
        "LogGroupName": "/aws/lambda/" + GUARD, "RetentionInDays": 7})
    add("GuardRole", "IAM::Role", {
        "RoleName": GUARD,
        "AssumeRolePolicyDocument": {"Version": "2012-10-17", "Statement": [{
            "Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"},
            "Action": "sts:AssumeRole"}]},
        "Policies": [{"PolicyName": "StopOnlyTeamBackend", "PolicyDocument": {
            "Version": "2012-10-17", "Statement": [
                allow("budgets:ViewBudget", budget_arn),
                allow("lambda:PutFunctionConcurrency", arn("lambda", "function:" + TARGET)),
                allow("apigateway:PATCH", f"arn:aws:apigateway:{REGION}::/apis/{API}/routes/{ROUTE}"),
                allow("sns:Publish", ref("Alerts")),
                allow(["logs:CreateLogStream", "logs:PutLogEvents"],
                      arn("logs", "log-group:/aws/lambda/" + GUARD + ":*")),
            ]}}]})
    add("Guard", "Lambda::Function", {
        "FunctionName": GUARD, "Runtime": "python3.13", "Architectures": ["arm64"],
        "Handler": "index.handler", "MemorySize": 128, "Timeout": 60,
        "Role": {"Fn::GetAtt": ["GuardRole", "Arn"]},
        "Environment": {"Variables": {"ACCOUNT_ID": ACCOUNT, "BUDGET_NAME": BUDGET,
            "LIMIT_USD": "14", "TARGET_FUNCTION": TARGET, "API_ID": API, "ROUTE_ID": ROUTE,
            "TRIGGER_TOPIC": ref("Trigger"), "ALERT_TOPIC": ref("Alerts")}},
        "Code": {"ZipFile": Path(__file__).with_name("budget_guard.py").read_text()},
    }, DependsOn=["GuardLog"])
    add("InvokeGuard", "Lambda::Permission", {
        "FunctionName": ref("Guard"), "Action": "lambda:InvokeFunction",
        "Principal": "sns.amazonaws.com", "SourceArn": ref("Trigger"), "SourceAccount": ACCOUNT})
    add("GuardSubscription", "SNS::Subscription", {
        "TopicArn": ref("Trigger"), "Protocol": "lambda",
        "Endpoint": {"Fn::GetAtt": ["Guard", "Arn"]}}, DependsOn=["InvokeGuard"])

    notifications = []
    for amount in (7, 11.2, 14):
        subscribers = [{"SubscriptionType": "EMAIL", "Address": ref("AlertEmailAddress")}]
        if amount == 14:
            subscribers.append({"SubscriptionType": "SNS", "Address": ref("Trigger")})
        notifications.append({"Notification": {
            "NotificationType": "ACTUAL", "ComparisonOperator": "GREATER_THAN",
            "ThresholdType": "ABSOLUTE_VALUE", "Threshold": amount},
            "Subscribers": subscribers})
    add("SemesterBudget", "Budgets::Budget", {
        "Budget": {"BudgetName": BUDGET, "BudgetType": "COST", "TimeUnit": "CUSTOM",
            "BudgetLimit": {"Amount": 14, "Unit": "USD"},
            "TimePeriod": {"Start": "2026-10-01T00:00:00Z", "End": "2027-01-01T00:00:00Z"},
            "CostTypes": {"IncludeCredit": False, "IncludeRefund": False,
                          "UseBlended": False, "UseAmortized": False}},
        "NotificationsWithSubscribers": notifications,
    }, DependsOn=["TopicPolicies", "GuardSubscription"])

    alarms = [
        ("Traffic", "AWS/ApiGateway", "Count", "Sum", 180, 3, 5, "ApiId", API),
        ("RejectedRequests", "AWS/ApiGateway", "4xx", "Sum", 30, 2, 3, "ApiId", API),
        ("ApiErrors", "AWS/ApiGateway", "5xx", "Sum", 5, 2, 3, "ApiId", API),
        ("SlowApi", "AWS/ApiGateway", "Latency", "Average", 1000, 2, 3, "ApiId", API),
        ("Concurrency", "AWS/Lambda", "ConcurrentExecutions", "Maximum", 8, 2, 3, "FunctionName", TARGET),
        ("GuardFailure", "AWS/Lambda", "Errors", "Sum", 1, 1, 1, "FunctionName", GUARD),
    ]
    for name, namespace, metric, statistic, limit, points, periods, dimension, value in alarms:
        add(name + "Alarm", "CloudWatch::Alarm", {
            "AlarmName": PREFIX + "-" + name.lower(),
            "AlarmDescription": "Team backend warning; email only. Only the cost budget triggers shutdown.",
            "Namespace": namespace, "MetricName": metric, "Statistic": statistic,
            "Dimensions": [{"Name": dimension, "Value": value}], "Period": 60,
            "EvaluationPeriods": periods, "DatapointsToAlarm": points, "Threshold": limit,
            "ComparisonOperator": "GreaterThanOrEqualToThreshold", "TreatMissingData": "notBreaching",
            "AlarmActions": [ref("Alerts")], "OKActions": [ref("Alerts")],
        }, DependsOn=["TopicPolicies"])
    return {"AWSTemplateFormatVersion": "2010-09-09",
            "Description": "Team 3 semester budget alerts and delayed billing safety switch",
            "Parameters": {"AlertEmailAddress": {"Type": "String", "NoEcho": True,
                                                "Description": "Owner email for budget and traffic alerts"}},
            "Resources": resources,
            "Outputs": {"AlertTopic": {"Value": ref("Alerts")}, "TriggerTopic": {"Value": ref("Trigger")},
                        "BudgetName": {"Value": BUDGET}, "GuardFunction": {"Value": ref("Guard")}}}


if __name__ == "__main__":
    print(json.dumps(template(), indent=2))
