"""Run in AWS CloudShell to configure the team's throttled public HTTP API.

First run without flags, migrate/test clients, then run with --close-direct-url.
Uses the current AWS session; never creates access keys or changes account plans.
"""

import argparse
import json

import boto3


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--close-direct-url", action="store_true")
    args = parser.parse_args()
    region = "us-west-2"
    account = "398074591774"
    function = "csci201-team3-api"
    session = boto3.Session(region_name=region)
    if session.client("sts").get_caller_identity()["Account"] != account:
        raise SystemExit("Wrong AWS account; no changes made.")
    gateway = session.client("apigatewayv2")
    lamb = session.client("lambda")
    function_arn = lamb.get_function_configuration(FunctionName=function)["FunctionArn"]
    name = "csci201-team3-public-api"
    apis = []
    for page in gateway.get_paginator("get_apis").paginate():
        apis.extend(api for api in page["Items"] if api["Name"] == name)
    if len(apis) > 1:
        raise SystemExit("Multiple team APIs found; refusing to choose one.")
    cors = {
        "AllowOrigins": [
            "https://csci201-expense-tracker.pages.dev",
            "http://localhost:5173",
            "http://127.0.0.1:5178",
        ],
        "AllowMethods": ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
        "AllowHeaders": ["authorization", "content-type", "idempotency-key"],
        "MaxAge": 300,
    }
    api = apis[0] if apis else gateway.create_api(
        Name=name, ProtocolType="HTTP", CorsConfiguration=cors,
        Tags={"Project": "CSCI201-Team3"},
    )
    api_id = api["ApiId"]
    gateway.update_api(ApiId=api_id, CorsConfiguration=cors)
    integrations = gateway.get_integrations(ApiId=api_id).get("Items", [])
    matches = [item for item in integrations if item.get("IntegrationUri") == function_arn]
    if len(matches) > 1:
        raise SystemExit("Multiple matching integrations found; review the API.")
    integration = matches[0] if matches else gateway.create_integration(
        ApiId=api_id, IntegrationType="AWS_PROXY", IntegrationUri=function_arn,
        PayloadFormatVersion="2.0", TimeoutInMillis=15000,
    )
    target = "integrations/" + integration["IntegrationId"]
    routes = gateway.get_routes(ApiId=api_id).get("Items", [])
    existing = next((route for route in routes if route["RouteKey"] == "$default"), None)
    if existing:
        if existing.get("Target") != target:
            raise SystemExit("Default route targets a different integration; review it.")
    else:
        gateway.create_route(ApiId=api_id, RouteKey="$default", Target=target)
    limits = {"ThrottlingRateLimit": 5.0, "ThrottlingBurstLimit": 10,
              "DetailedMetricsEnabled": False}
    stages = gateway.get_stages(ApiId=api_id).get("Items", [])
    if any(stage["StageName"] == "$default" for stage in stages):
        gateway.update_stage(ApiId=api_id, StageName="$default", AutoDeploy=True,
                             DefaultRouteSettings=limits)
    else:
        gateway.create_stage(ApiId=api_id, StageName="$default", AutoDeploy=True,
                             DefaultRouteSettings=limits)
    sid = "TeamHttpApiGateway"
    source = f"arn:aws:execute-api:{region}:{account}:{api_id}/*/*"
    try:
        policy = json.loads(lamb.get_policy(FunctionName=function)["Policy"])
    except lamb.exceptions.ResourceNotFoundException:
        policy = {"Statement": []}
    statement = next((s for s in policy["Statement"] if s["Sid"] == sid), None)
    if statement:
        if (statement.get("Action") != "lambda:InvokeFunction"
                or statement.get("Condition", {}).get("StringEquals", {}).get("AWS:SourceAccount") != account
                or statement.get("Principal") != {"Service": "apigateway.amazonaws.com"}
                or statement.get("Condition", {}).get("ArnLike", {}).get("AWS:SourceArn") != source):
            raise SystemExit("Existing gateway permission differs; review it.")
    else:
        lamb.add_permission(FunctionName=function, StatementId=sid,
                            Action="lambda:InvokeFunction",
                            Principal="apigateway.amazonaws.com",
                            SourceArn=source, SourceAccount=account)
    if args.close_direct_url:
        try:
            lamb.update_function_url_config(FunctionName=function, AuthType="AWS_IAM")
        except lamb.exceptions.ResourceNotFoundException:
            pass
        for old_sid in ("TeamFunctionUrl", "TeamFunctionUrlInvoke"):
            try:
                lamb.remove_permission(FunctionName=function, StatementId=old_sid)
            except lamb.exceptions.ResourceNotFoundException:
                pass
    print(json.dumps({"api_id": api_id, "api_url": api["ApiEndpoint"],
                      "requests_per_second": 5, "burst": 10,
                      "direct_url_closed": args.close_direct_url}, indent=2))


if __name__ == "__main__":
    main()
