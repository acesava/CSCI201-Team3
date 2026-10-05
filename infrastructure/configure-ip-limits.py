"""Preview per-IP counter infrastructure; pass --apply to configure it in CloudShell.

Run before deploying the Java limiter. Does not upgrade the account or create keys.
"""
import argparse
import json
import boto3

ACCOUNT = "398074591774"
REGION = "us-west-2"
TABLE = "csci201-team3-ip-limits"
FUNCTION = "csci201-team3-api"
ROLE = "csci201-team3-lambda"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    session = boto3.Session(region_name=REGION)
    if session.client("sts").get_caller_identity()["Account"] != ACCOUNT:
        raise SystemExit("Wrong AWS account; no changes made.")
    db = session.client("dynamodb")
    lamb = session.client("lambda")
    iam = session.client("iam")
    config = lamb.get_function_configuration(FunctionName=FUNCTION)
    if config["Role"] != f"arn:aws:iam::{ACCOUNT}:role/{ROLE}":
        raise SystemExit("Unexpected Lambda execution role; no changes made.")
    tables = []
    for page in db.get_paginator("list_tables").paginate():
        tables.extend(page["TableNames"])
    current = None
    for name in tables:
        table = db.describe_table(TableName=name)["Table"]
        if name == TABLE:
            current = table
        elif table["ProvisionedThroughput"]["ReadCapacityUnits"] or table["ProvisionedThroughput"]["WriteCapacityUnits"]:
            raise SystemExit("Other provisioned tables share the free allowance; review capacity first.")
    if current:
        if (current.get("BillingModeSummary", {}).get("BillingMode", "PROVISIONED") != "PROVISIONED"
                or current["KeySchema"] != [{"AttributeName": "pk", "KeyType": "HASH"}]
                or current["ProvisionedThroughput"]["ReadCapacityUnits"] != 25
                or current["ProvisionedThroughput"]["WriteCapacityUnits"] != 25
                or current.get("GlobalSecondaryIndexes")):
            raise SystemExit("Existing counter table differs; review it before changing anything.")
    policy = {"Version": "2012-10-17", "Statement": [{
        "Effect": "Allow", "Action": "dynamodb:UpdateItem",
        "Resource": f"arn:aws:dynamodb:{REGION}:{ACCOUNT}:table/{TABLE}",
    }]}
    print(json.dumps({"table": TABLE, "provisioned_reads": 25, "provisioned_writes": 25,
                      "auto_scaling": False, "role": ROLE, "policy": policy,
                      "lambda_environment_key": "IP_RATE_LIMIT_TABLE", "apply": args.apply}, indent=2))
    if not args.apply:
        return
    if current is None:
        db.create_table(TableName=TABLE,
                        AttributeDefinitions=[{"AttributeName": "pk", "AttributeType": "S"}],
                        KeySchema=[{"AttributeName": "pk", "KeyType": "HASH"}],
                        BillingMode="PROVISIONED",
                        ProvisionedThroughput={"ReadCapacityUnits": 25, "WriteCapacityUnits": 25},
                        TableClass="STANDARD", Tags=[{"Key": "Project", "Value": "CSCI201-Team3"}])
        db.get_waiter("table_exists").wait(TableName=TABLE, WaiterConfig={"Delay": 2, "MaxAttempts": 60})
    ttl = db.describe_time_to_live(TableName=TABLE)["TimeToLiveDescription"]
    if ttl["TimeToLiveStatus"] == "DISABLED":
        db.update_time_to_live(TableName=TABLE, TimeToLiveSpecification={"Enabled": True, "AttributeName": "expiresAt"})
    elif ttl.get("AttributeName") != "expiresAt" or ttl["TimeToLiveStatus"] not in ("ENABLED", "ENABLING"):
        raise SystemExit("Unexpected TTL configuration; review before granting permissions.")
    iam.put_role_policy(RoleName=ROLE, PolicyName="TeamIpRateCounter", PolicyDocument=json.dumps(policy))
    variables = config.get("Environment", {}).get("Variables", {})
    if variables.get("IP_RATE_LIMIT_TABLE") != TABLE:
        variables["IP_RATE_LIMIT_TABLE"] = TABLE
        lamb.update_function_configuration(FunctionName=FUNCTION, RevisionId=config["RevisionId"],
                                           Environment={"Variables": variables})
        lamb.get_waiter("function_updated_v2").wait(FunctionName=FUNCTION)
    print("Counter configured. Deploy the Java limiter next, then verify public requests.")


if __name__ == "__main__":
    main()
