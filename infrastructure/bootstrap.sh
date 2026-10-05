#!/usr/bin/env bash
# Run in AWS CloudShell only after reviewing the account, permissions, and public URL.
set -euo pipefail
export AWS_REGION=us-west-2
export AWS_DEFAULT_REGION=us-west-2
export AWS_PAGER=''
EXPECTED_ACCOUNT=398074591774
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
[[ "$ACCOUNT_ID" == "$EXPECTED_ACCOUNT" ]] || { echo 'Wrong AWS account; stopping.' >&2; exit 1; }
cd "$(dirname "$0")/.."
[[ -f backend/target/expense-api.jar ]] || { echo 'Run backend/mvnw verify first.' >&2; exit 1; }
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
FUNCTION=csci201-team3-api
FUNCTION_ARN="arn:aws:lambda:$AWS_REGION:$ACCOUNT_ID:function:$FUNCTION"
EXEC_ROLE=csci201-team3-lambda
DEPLOY_ROLE=csci201-team3-github-deploy
LOG_GROUP="/aws/lambda/$FUNCTION"

cat > "$WORK_DIR/lambda-trust.json" <<'JSON'
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
JSON
cat > "$WORK_DIR/logs-policy.json" <<JSON
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["logs:CreateLogStream","logs:PutLogEvents"],"Resource":"arn:aws:logs:$AWS_REGION:$ACCOUNT_ID:log-group:$LOG_GROUP:*"}]}
JSON
if ! aws iam get-role --role-name "$EXEC_ROLE" >/dev/null 2>&1; then
  aws iam create-role --role-name "$EXEC_ROLE" --assume-role-policy-document "file://$WORK_DIR/lambda-trust.json" >/dev/null
fi
aws iam put-role-policy --role-name "$EXEC_ROLE" --policy-name TeamFunctionLogs --policy-document "file://$WORK_DIR/logs-policy.json"
if [[ $(aws logs describe-log-groups --log-group-name-prefix "$LOG_GROUP" --query "length(logGroups[?logGroupName=='$LOG_GROUP'])" --output text) == 0 ]]; then
  aws logs create-log-group --log-group-name "$LOG_GROUP"
fi
aws logs put-retention-policy --log-group-name "$LOG_GROUP" --retention-in-days 7
if ! aws lambda get-function --function-name "$FUNCTION" >/dev/null 2>&1; then
  # IAM trust can take a few seconds to propagate. Retry only that specific failure.
  for ATTEMPT in 1 2 3 4 5 6; do
    if aws lambda create-function --function-name "$FUNCTION" --runtime java21 --architectures arm64 --handler edu.usc.csci201.team3.Handler::handleRequest --role "arn:aws:iam::$ACCOUNT_ID:role/$EXEC_ROLE" --memory-size 256 --timeout 10 --zip-file fileb://backend/target/expense-api.jar > "$WORK_DIR/function.json" 2> "$WORK_DIR/error.txt"; then
      break
    fi
    if ! grep -q 'cannot be assumed by Lambda' "$WORK_DIR/error.txt" || [[ "$ATTEMPT" == 6 ]]; then cat "$WORK_DIR/error.txt" >&2; exit 1; fi
    sleep 5
  done
fi
aws lambda wait function-active-v2 --function-name "$FUNCTION"
if ! aws lambda get-function-url-config --function-name "$FUNCTION" >/dev/null 2>&1; then
  aws lambda create-function-url-config --function-name "$FUNCTION" --auth-type NONE >/dev/null
fi
# Both permissions are required for new function URLs. These expose only this starter API.
for SID in TeamFunctionUrl TeamFunctionUrlInvoke; do
  POLICY=$(aws lambda get-policy --function-name "$FUNCTION" --query Policy --output text 2>/dev/null || true)
  if [[ "$POLICY" != *"\"$SID\""* ]]; then
    if [[ "$SID" == TeamFunctionUrl ]]; then
      aws lambda add-permission --function-name "$FUNCTION" --statement-id "$SID" --action lambda:InvokeFunctionUrl --principal '*' --function-url-auth-type NONE >/dev/null
    else
      aws lambda add-permission --function-name "$FUNCTION" --statement-id "$SID" --action lambda:InvokeFunction --principal '*' --invoked-via-function-url >/dev/null
    fi
  fi
done

PROVIDER_ARN="arn:aws:iam::$ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com"
if ! aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$PROVIDER_ARN" >/dev/null 2>&1; then
  aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com --client-id-list sts.amazonaws.com >/dev/null
fi
cat > "$WORK_DIR/github-trust.json" <<JSON
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Federated":"$PROVIDER_ARN"},"Action":"sts:AssumeRoleWithWebIdentity","Condition":{"StringEquals":{"token.actions.githubusercontent.com:aud":"sts.amazonaws.com","token.actions.githubusercontent.com:sub":"repo:acesava@287342120/CSCI201-Team3@1405003751:ref:refs/heads/main"}}}]}
JSON
cat > "$WORK_DIR/deploy-policy.json" <<JSON
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["lambda:UpdateFunctionCode","lambda:GetFunctionConfiguration","lambda:InvokeFunction"],"Resource":"$FUNCTION_ARN"}]}
JSON
if ! aws iam get-role --role-name "$DEPLOY_ROLE" >/dev/null 2>&1; then
  aws iam create-role --role-name "$DEPLOY_ROLE" --assume-role-policy-document "file://$WORK_DIR/github-trust.json" >/dev/null
fi
aws iam update-assume-role-policy --role-name "$DEPLOY_ROLE" --policy-document "file://$WORK_DIR/github-trust.json"
aws iam put-role-policy --role-name "$DEPLOY_ROLE" --policy-name DeployTeamFunction --policy-document "file://$WORK_DIR/deploy-policy.json"
aws lambda get-function-url-config --function-name "$FUNCTION" --query FunctionUrl --output text
echo "AWS_DEPLOY_ROLE_ARN=arn:aws:iam::$ACCOUNT_ID:role/$DEPLOY_ROLE"
echo 'Configure exact frontend CORS origins after the Pages hostname is known.'
