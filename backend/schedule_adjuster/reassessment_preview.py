"""Stateless signed previews: commit the approved agent output without resampling."""
import base64
import hashlib
import hmac
import json
import os
import secrets
import time

from .reassessment import ReassessmentPreviewExpired

_configured = os.environ.get("APP_REASSESSMENT_SIGNING_KEY")
_KEY = hashlib.sha256(_configured.encode()).digest() if _configured else secrets.token_bytes(32)
PREVIEW_TTL_SECONDS = 900


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def issue(context, rows):
    payload = json.dumps({"version": 1, "expires": int(time.time()) + PREVIEW_TTL_SECONDS,
                          "context": digest(context), "rows": digest(rows)}, sort_keys=True).encode()
    encoded = base64.urlsafe_b64encode(payload).decode().rstrip("=")
    signature = hmac.new(_KEY, encoded.encode(), hashlib.sha256).hexdigest()
    return encoded + "." + signature


def verify(token, context, rows):
    try:
        if not isinstance(token, str) or len(token) > 1024:
            raise ValueError()
        encoded, signature = token.split(".")
        expected = hmac.new(_KEY, encoded.encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(signature, expected):
            raise ValueError()
        payload = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
        if payload.get("version") != 1 or type(payload.get("expires")) is not int or payload["expires"] <= time.time() or payload.get("context") != digest(context) or payload.get("rows") != digest(rows):
            raise ValueError()
    except (ValueError, TypeError, UnicodeError, AttributeError):
        raise ReassessmentPreviewExpired("Preview expired or inputs changed. Preview the plan again.") from None
