"""In-memory rolling-window admission limits for the budgeted POST routes."""
from __future__ import annotations

import math
import threading
import time
from collections import deque
from dataclasses import dataclass
from typing import Callable


@dataclass(frozen=True)
class RouteLimits:
    """Admissions one route allows per window for one device owner, one client IP and the whole service."""
    per_owner: int
    per_ip: int
    service: int

    def __post_init__(self) -> None:
        if min(self.per_owner, self.per_ip, self.service) < 1:
            raise ValueError("admission limits must be positive")


WINDOW_SECONDS = 3600
REASSESSMENT_PREVIEW_BUCKET = "reassessment_preview"
_ADJUSTMENT_LIMITS = RouteLimits(per_owner=12, per_ip=40, service=60)
DEFAULT_ROUTE_LIMITS = {
    "/api/v1/study-plans": RouteLimits(per_owner=3, per_ip=12, service=24),
    "/api/v1/schedules/adjust": _ADJUSTMENT_LIMITS,
    REASSESSMENT_PREVIEW_BUCKET: _ADJUSTMENT_LIMITS,
}


class AdmissionLimiter:
    """Reserve one slot in each applicable bucket, or none, under a single lock.

    State lives only in this object, so a new server process starts with empty
    counters.  Keys are the owner digest and the resolved client IP; the raw
    device token is never stored.
    """

    def __init__(self, limits: dict[str, RouteLimits] | None = None, *, window_seconds: float = WINDOW_SECONDS,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self._limits = dict(DEFAULT_ROUTE_LIMITS if limits is None else limits)
        self._window = window_seconds
        self._clock = clock
        self._lock = threading.Lock()
        self._buckets: dict[tuple[str, str, str], deque[float]] = {}

    def admit(self, route: str, owner: str, client_ip: str) -> int | None:
        """Return None after reserving the request, or the whole seconds until every full bucket has room."""
        limits = self._limits[route]
        keys = ((route, "owner", owner), (route, "ip", client_ip), (route, "service", ""))
        with self._lock:
            now = self._clock()
            self._expire(now)
            waits = [self._buckets[key][0] + self._window - now
                     for key, limit in zip(keys, (limits.per_owner, limits.per_ip, limits.service))
                     if len(self._buckets.get(key, ())) >= limit]
            if waits:
                return max(1, math.ceil(max(waits)))
            for key in keys:
                self._buckets.setdefault(key, deque()).append(now)
            return None

    def _expire(self, now: float) -> None:
        """Drop admissions older than the window and forget buckets left empty."""
        for key in list(self._buckets):
            bucket = self._buckets[key]
            while bucket and bucket[0] <= now - self._window:
                bucket.popleft()
            if not bucket:
                del self._buckets[key]
