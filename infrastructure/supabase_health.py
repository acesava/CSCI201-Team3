"""Check Team 3's Supabase database directly; this script never calls AWS."""

import argparse
import http.client
import json
import os
import re
import sys
import urllib.error
import urllib.request


HEALTH_URL = "https://klxskdrnvslhqrwmuiuu.supabase.co/rest/v1/rpc/check_database_health"
TIMEOUT_SECONDS = 15
MAX_RESPONSE_BYTES = 64


class HealthCheckError(Exception):
    """A safe, credential-free failure message for workflow logs."""


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward the API key to a redirected destination.
        raise HealthCheckError("redirect refused")


def check_database(key, opener=None):
    if not key or not re.fullmatch(r"sb_publishable_[A-Za-z0-9_-]+", key):
        raise HealthCheckError("SUPABASE_HEALTH_KEY must be a publishable key")

    request = urllib.request.Request(
        HEALTH_URL,
        data=b"{}",
        headers={"apikey": key, "Content-Type": "application/json"},
        method="POST",
    )
    if opener is None:
        opener = urllib.request.build_opener(NoRedirectHandler())

    try:
        with opener.open(request, timeout=TIMEOUT_SECONDS) as response:
            if response.status != 200:
                raise HealthCheckError("unexpected HTTP status")
            body = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as error:
        raise HealthCheckError(f"HTTP {error.code}") from None
    except (urllib.error.URLError, OSError, http.client.HTTPException):
        raise HealthCheckError("network request failed") from None

    if len(body) > MAX_RESPONSE_BYTES:
        raise HealthCheckError("response too large")
    try:
        result = json.loads(body)
    except (ValueError, UnicodeError):
        raise HealthCheckError("invalid JSON response") from None
    if type(result) is not int or result != 1:
        raise HealthCheckError("unexpected database response")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    try:
        check_database(os.environ.get("SUPABASE_HEALTH_KEY"))
    except HealthCheckError as error:
        print(f"Supabase database health check failed: {error}.", file=sys.stderr)
        return 1
    print("Supabase database health check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
