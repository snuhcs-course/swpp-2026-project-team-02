"""Admission limits for HTTP tests whose subject is not the request quota."""
from __future__ import annotations

from api_admission import DEFAULT_ROUTE_LIMITS, AdmissionLimiter, RouteLimits


def roomy_admission() -> AdmissionLimiter:
    """A fresh limiter large enough that many requests in one test never reach a quota."""
    return AdmissionLimiter({route: RouteLimits(10_000, 10_000, 10_000) for route in DEFAULT_ROUTE_LIMITS})
