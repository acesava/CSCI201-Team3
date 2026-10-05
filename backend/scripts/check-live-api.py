"""Check the public API Gateway health endpoint and browser CORS after deployment."""

import json
import sys
import urllib.request


def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: python3 check-live-api.py API_BASE_URL FRONTEND_ORIGIN")
    base_url, origin = sys.argv[1:]
    request = urllib.request.Request(
        base_url.rstrip("/") + "/health", headers={"Origin": origin}
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        if response.status != 200:
            raise SystemExit(f"Unexpected HTTP status: {response.status}")
        if response.headers.get("Access-Control-Allow-Origin") != origin:
            raise SystemExit(f"CORS does not allow {origin}")
        if json.load(response).get("status") != "ok":
            raise SystemExit("Public health endpoint did not report ok")
    print(f"Public Java health endpoint and CORS passed for {origin}")


if __name__ == "__main__":
    main()
