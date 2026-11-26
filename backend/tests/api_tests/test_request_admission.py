"""Rolling-hour admission limits and client-address resolution for the budgeted POST routes."""
from __future__ import annotations

import email.message
import http.client
import json
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import api_service
from api_admission import DEFAULT_ROUTE_LIMITS, AdmissionLimiter, RouteLimits
from api_server import DEFAULT_API_CONFIG, make_handler, proxied_client_ip

GENERATE = "/api/v1/study-plans"
ADJUST = "/api/v1/schedules/adjust"
RATE_LIMITED_BODY = {"error": {"code": "RATE_LIMITED", "message": "Too many requests. Try again later."}}
CLIENT_ADDRESS_BODY = {"error": {"code": "SERVICE_UNAVAILABLE",
                                 "message": "The service cannot identify the client address. Try again later."}}
PROXY_CONFIG = {**DEFAULT_API_CONFIG, "trust_local_proxy": True}


def token(number: int) -> str:
    return f"{number:064x}"


class FakeClock:
    def __init__(self, now: float = 1000.0):
        self.now = now

    def __call__(self) -> float:
        return self.now


def headers(*pairs: tuple[str, str]) -> email.message.Message:
    message = email.message.Message()
    for name, value in pairs:
        message[name] = value
    return message


class AdmissionLimiterTests(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()

    def limiter(self, limits=None):
        return AdmissionLimiter(limits, clock=self.clock)

    def admitted(self, limiter, route, owner, ip, attempts):
        return sum(limiter.admit(route, owner, ip) is None for _ in range(attempts))

    def test_default_limits_bound_each_scope_of_each_route(self):
        for route, (per_owner, per_ip, service) in ((GENERATE, (3, 12, 24)), (ADJUST, (12, 40, 60))):
            with self.subTest(route=route):
                self.assertEqual(DEFAULT_ROUTE_LIMITS[route], RouteLimits(per_owner, per_ip, service))
                limiter = self.limiter()
                # The same owner from another address is still limited by its own bucket.
                self.assertEqual(self.admitted(limiter, route, "owner-0", "198.51.100.1", per_owner + 2), per_owner)
                self.assertIsNotNone(limiter.admit(route, "owner-0", "198.51.100.2"))
                # Fresh owners on one address stop at the address limit.
                owners = iter(range(1, 1000))
                while self.admitted(limiter, route, f"owner-{next(owners)}", "198.51.100.1", per_owner):
                    pass
                self.assertEqual(self.count(limiter, route, "ip", "198.51.100.1"), per_ip)
                # Fresh owners on fresh addresses stop at the service limit.
                addresses = iter(range(2, 1000))
                while self.admitted(limiter, route, f"owner-{next(owners)}", f"203.0.113.{next(addresses)}", 1):
                    pass
                self.assertEqual(self.count(limiter, route, "service", ""), service)

    @staticmethod
    def count(limiter, route, scope, key):
        return len(limiter._buckets[(route, scope, key)])

    def test_routes_have_separate_buckets(self):
        limiter = self.limiter()
        self.assertEqual(self.admitted(limiter, GENERATE, "owner", "198.51.100.1", 5), 3)
        self.assertEqual(self.admitted(limiter, ADJUST, "owner", "198.51.100.1", 14), 12)
        self.assertIsNotNone(limiter.admit(GENERATE, "owner", "198.51.100.1"))

    def test_a_rejected_request_reserves_no_slot_in_any_bucket(self):
        limiter = self.limiter()
        self.assertEqual(self.admitted(limiter, GENERATE, "first", "198.51.100.1", 3), 3)
        for _ in range(20):
            self.assertIsNotNone(limiter.admit(GENERATE, "first", "198.51.100.1"))
        # Rejections charged nothing to the shared address or service buckets.
        for owner in ("second", "third", "fourth"):
            self.assertEqual(self.admitted(limiter, GENERATE, owner, "198.51.100.1", 3), 3)
        self.assertIsNotNone(limiter.admit(GENERATE, "fifth", "198.51.100.1"))
        self.assertEqual(self.count(limiter, GENERATE, "service", ""), 12)

    def test_retry_after_is_whole_seconds_until_every_full_bucket_has_room(self):
        limiter = self.limiter({GENERATE: RouteLimits(per_owner=1, per_ip=2, service=10)})
        self.assertIsNone(limiter.admit(GENERATE, "other", "198.51.100.1"))  # t=1000, fills IP to 1 of 2
        self.clock.now = 1050
        self.assertIsNone(limiter.admit(GENERATE, "owner", "198.51.100.1"))  # owner 1 of 1, IP 2 of 2
        self.clock.now = 1100
        # The owner bucket frees last (1050 + 3600); the IP bucket frees at 1000 + 3600.
        self.assertEqual(limiter.admit(GENERATE, "owner", "198.51.100.1"), 3550)
        self.clock.now = 4599.5
        self.assertEqual(limiter.admit(GENERATE, "owner", "198.51.100.1"), 51)
        self.clock.now = 4600
        self.assertEqual(limiter.admit(GENERATE, "owner", "198.51.100.1"), 50)  # IP has room, owner does not
        self.clock.now = 4649.9
        self.assertEqual(limiter.admit(GENERATE, "owner", "198.51.100.1"), 1)
        self.clock.now = 4650
        self.assertIsNone(limiter.admit(GENERATE, "owner", "198.51.100.1"))

    def test_expired_admissions_and_empty_buckets_are_forgotten(self):
        limiter = self.limiter()
        for number in range(10):
            limiter.admit(ADJUST, f"owner-{number}", f"203.0.113.{number}")
        self.clock.now += 3600
        limiter.admit(ADJUST, "late", "198.51.100.1")
        self.assertEqual(set(limiter._buckets), {(ADJUST, "owner", "late"), (ADJUST, "ip", "198.51.100.1"),
                                                 (ADJUST, "service", "")})

    def test_parallel_admissions_never_exceed_a_limit(self):
        limiter = AdmissionLimiter()
        barrier = threading.Barrier(40)
        results = []

        def attempt(number):
            barrier.wait()
            results.append(limiter.admit(ADJUST, f"owner-{number % 5}", "198.51.100.1"))

        threads = [threading.Thread(target=attempt, args=(number,)) for number in range(40)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=5)
        # Five owners could take 60 slots, but the address allows 40; all 40 fit, then the next does not.
        self.assertEqual(results.count(None), 40)
        self.assertIsNotNone(limiter.admit(ADJUST, "owner-new", "198.51.100.1"))

        limiter = AdmissionLimiter()
        barrier = threading.Barrier(30)
        results = []

        def same_owner():
            barrier.wait()
            results.append(limiter.admit(GENERATE, "owner", "198.51.100.1"))

        threads = [threading.Thread(target=same_owner) for _ in range(30)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=5)
        self.assertEqual(results.count(None), 3)

    def test_limits_must_be_positive(self):
        with self.assertRaises(ValueError):
            RouteLimits(0, 1, 1)


class ProxiedClientAddressTests(unittest.TestCase):
    def test_local_mode_uses_the_socket_peer_and_ignores_forwarding_headers(self):
        supplied = headers(("X-Real-IP", "203.0.113.7"), ("X-Forwarded-For", "203.0.113.8"))
        self.assertEqual(proxied_client_ip("127.0.0.1", supplied, False), "127.0.0.1")
        self.assertEqual(proxied_client_ip("198.51.100.4", supplied, False), "198.51.100.4")

    def test_proxy_mode_trusts_only_a_loopback_peer(self):
        supplied = headers(("X-Real-IP", "203.0.113.7"), ("X-Forwarded-For", "203.0.113.8"))
        for peer in ("127.0.0.1", "127.0.0.2", "::1", "::ffff:127.0.0.1"):
            with self.subTest(peer=peer):
                self.assertEqual(proxied_client_ip(peer, supplied, True), "203.0.113.7")
        self.assertEqual(proxied_client_ip("198.51.100.4", supplied, True), "198.51.100.4")
        self.assertEqual(proxied_client_ip("198.51.100.4", headers(), True), "198.51.100.4")
        self.assertEqual(proxied_client_ip("::1", headers(("X-Real-IP", "2001:db8::1")), True), "2001:db8::1")

    def test_proxy_mode_fails_closed_without_one_valid_address(self):
        cases = {"missing": headers(), "only forwarded chain": headers(("X-Forwarded-For", "203.0.113.8")),
                 "duplicate": headers(("X-Real-IP", "203.0.113.7"), ("X-Real-IP", "203.0.113.7")),
                 "empty": headers(("X-Real-IP", "")), "list": headers(("X-Real-IP", "203.0.113.7, 203.0.113.8")),
                 "host name": headers(("X-Real-IP", "example.com")),
                 "with port": headers(("X-Real-IP", "203.0.113.7:443")),
                 "out of range": headers(("X-Real-IP", "203.0.113.256"))}
        for label, supplied in cases.items():
            with self.subTest(label):
                self.assertIsNone(proxied_client_ip("127.0.0.1", supplied, True))


class ProviderUsed(AssertionError):
    pass


def forbidden(*args, **kwargs):
    raise ProviderUsed("an admission or address rejection must not reach provider or profile work")


class RoomyBacklogServer(ThreadingHTTPServer):
    # The default listen backlog of 5 makes macOS reset simultaneous connections before they are accepted.
    request_queue_size = 64


class AdmissionHttpTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.plan_root = Path(temp.name) / "plans"
        self.plan_root.mkdir()
        for name in ("PlanningAgent", "AdjustmentAgent", "load_personal_availability", "load_adjuster_availability"):
            patcher = patch.object(api_service, name, forbidden)
            patcher.start()
            self.addCleanup(patcher.stop)
        patcher = patch.object(api_service, "STUDY_PLAN_OUTPUT_DIR", self.plan_root)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.clock = FakeClock()

    def start_server(self, config=DEFAULT_API_CONFIG):
        server = RoomyBacklogServer(("127.0.0.1", 0), make_handler(config, AdmissionLimiter(clock=self.clock)))
        server.RequestHandlerClass.log_message = lambda *args: None
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(lambda: (server.shutdown(), server.server_close(), thread.join(timeout=3)))
        self.port = server.server_port

    def send(self, method, path, owner=None, body=b"{}", extra=()):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            connection.putrequest(method, path)
            sent = list(extra)
            if owner is not None:
                sent.append(("Authorization", f"Bearer {token(owner)}"))
            if body is not None:
                sent += [("Content-Type", "application/json"), ("Content-Length", str(len(body)))]
            for name, value in sent:
                connection.putheader(name, value)
            connection.endheaders(body)
            raw = connection.getresponse()
            return SimpleNamespace(status=raw.status, headers=raw.headers, body=raw.read())
        finally:
            connection.close()

    def statuses(self, path, owner, count, extra=(), body=b"{}"):
        return [self.send("POST", path, owner, body, extra).status for _ in range(count)]

    def test_admitted_requests_count_even_when_they_fail_and_rejections_are_fixed_429s(self):
        self.start_server()
        # Admitted bodies fail validation (422) and still use the owner's three generation slots.
        self.assertEqual(self.statuses(GENERATE, 1, 3), [422, 422, 422])
        # The fourth is rejected before the body is read: invalid JSON would otherwise be 422.
        response = self.send("POST", GENERATE, 1, b"{not json")
        self.assertEqual(response.status, 429)
        self.assertEqual(json.loads(response.body), RATE_LIMITED_BODY)
        self.assertEqual(response.headers.get_all("Retry-After"), ["3600"])
        self.assertEqual(response.headers["Cache-Control"], "no-store")
        self.assertEqual(list(self.plan_root.iterdir()), [], "a rejection creates no owner storage")
        # The adjustment route has its own bucket.
        self.assertEqual(self.send("POST", ADJUST, 1).status, 422)
        self.clock.now += 3599
        self.assertEqual(self.send("POST", GENERATE, 1).headers["Retry-After"], "1")
        self.clock.now += 1
        self.assertEqual(self.send("POST", GENERATE, 1).status, 422)

    def test_token_rejections_reads_and_preflight_consume_no_slots(self):
        self.start_server()
        for _ in range(30):
            self.assertEqual(self.send("POST", GENERATE).status, 401)
            self.assertEqual(self.send("POST", GENERATE, extra=[("Authorization", "Bearer short")]).status, 401)
        for _ in range(15):
            self.assertEqual(self.send("GET", GENERATE, 1, body=None).status, 200)
            self.assertEqual(self.send("OPTIONS", GENERATE, body=None).status, 204)
            self.assertEqual(self.send("GET", "/api/v1/health", body=None).status, 200)
        self.assertEqual(self.statuses(GENERATE, 1, 4), [422, 422, 422, 429])
        self.assertEqual(self.send("POST", GENERATE).status, 401, "the token check precedes admission")

    def test_every_adjustment_mode_shares_one_bucket(self):
        self.start_server()
        bodies = [{"mode": "automatic", "schedule_id": "study-1"}, {"mode": "automatic", "schedule_id": "external-1"},
                  {"mode": "manual"}, {"mode": "remaining"}, {"mode": "reassessment", "commit": True},
                  {"mode": "reassessment", "commit": 0}]
        for number in range(12):
            body = json.dumps(bodies[number % 4]).encode()
            self.assertEqual(self.send("POST", ADJUST, 7, body).status, 422)
        for body in bodies:
            self.assertEqual(self.send("POST", ADJUST, 7, json.dumps(body).encode()).status, 429)
        self.assertEqual(self.send("POST", GENERATE, 7).status, 422)

    def test_reassessment_previews_and_adjustments_have_independent_quotas(self):
        self.start_server()
        preview = b'{"mode":"reassessment","commit":false}'
        commit = b'{"mode":"reassessment","commit":true}'
        self.assertEqual(self.statuses(ADJUST, 1, 12, body=preview), [422] * 12)
        rejected = self.send("POST", ADJUST, 1, preview)
        self.assertEqual(rejected.status, 429)
        self.assertEqual(json.loads(rejected.body), RATE_LIMITED_BODY)
        self.assertEqual(rejected.headers["Retry-After"], "3600")
        self.assertEqual(self.send("POST", ADJUST, 1, commit).status, 422)
        self.assertEqual(self.send("POST", ADJUST, 1, b'{"mode":"manual"}').status, 422)
        self.assertEqual(self.statuses(ADJUST, 2, 12, body=commit), [422] * 12)
        self.assertEqual(self.send("POST", ADJUST, 2, commit).status, 429)
        self.assertEqual(self.send("POST", ADJUST, 2, preview).status, 422)

    def test_local_mode_keys_the_address_on_the_socket_peer(self):
        self.start_server()
        admitted = []
        for owner in range(1, 6):
            spoofed = [("X-Real-IP", f"203.0.113.{owner}"), ("X-Forwarded-For", f"198.51.100.{owner}")]
            admitted += self.statuses(GENERATE, owner, 3, spoofed)
        self.assertEqual(admitted.count(422), 12)
        self.assertEqual(admitted[12:], [429] * 3)

    def test_proxy_mode_keys_the_address_on_the_trusted_real_ip_only(self):
        self.start_server(PROXY_CONFIG)
        admitted = []
        for owner in range(1, 6):
            # A client-supplied forwarding chain cannot move a request to another address bucket.
            chain = [("X-Real-IP", "203.0.113.1"), ("X-Forwarded-For", f"198.51.100.{owner}")]
            admitted += self.statuses(GENERATE, owner, 3, chain)
        self.assertEqual(admitted.count(422), 12)
        self.assertEqual(admitted[12:], [429] * 3)
        # Another proxied address has its own bucket; the service ceiling still caps all addresses.
        statuses = []
        for owner in range(6, 16):
            statuses += self.statuses(GENERATE, owner, 3, [("X-Real-IP", f"203.0.113.{owner}")])
        self.assertEqual(statuses.count(422), 12)
        self.assertEqual(self.send("POST", GENERATE, 99, extra=[("X-Real-IP", "192.0.2.1")]).status, 429)

    def test_proxy_mode_without_one_valid_real_ip_fails_closed_before_any_charge(self):
        self.start_server(PROXY_CONFIG)
        cases = {"missing": [], "duplicate": [("X-Real-IP", "203.0.113.1"), ("X-Real-IP", "203.0.113.1")],
                 "invalid": [("X-Real-IP", "not-an-address")],
                 "forwarded only": [("X-Forwarded-For", "203.0.113.1")]}
        for path in (GENERATE, ADJUST):
            for label, extra in cases.items():
                with self.subTest(path=path, case=label), self.assertLogs("api_server", "WARNING") as logs:
                    response = self.send("POST", path, 1, b"{not json", extra)
                    self.assertNotIn(token(1), " ".join(logs.output))
                    self.assertEqual(response.status, 503)
                    self.assertEqual(json.loads(response.body), CLIENT_ADDRESS_BODY)
                    self.assertEqual(response.headers["Cache-Control"], "no-store")
                    self.assertIsNone(response.headers["Retry-After"])
        self.assertEqual(self.send("POST", GENERATE, 1, extra=[("X-Real-IP", "203.0.113.1")]).status, 422)
        self.assertEqual(self.send("POST", GENERATE, extra=[("X-Real-IP", "203.0.113.1")]).status, 401)
        self.assertEqual(self.send("GET", GENERATE, 1, body=None).status, 200, "reads need no proxied address")
        self.assertEqual(list(self.plan_root.iterdir()), [])

    def test_parallel_http_admissions_stop_at_the_owner_limit(self):
        self.start_server()
        barrier = threading.Barrier(12)
        statuses = []

        def post():
            barrier.wait()
            try:
                statuses.append(self.send("POST", GENERATE, 3).status)
            except OSError as error:
                statuses.append(repr(error))

        threads = [threading.Thread(target=post) for _ in range(12)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=10)
        self.assertEqual(sorted(statuses, key=str), [422] * 3 + [429] * 9)

    def test_a_new_server_starts_with_empty_counters(self):
        self.start_server()
        self.assertEqual(self.statuses(GENERATE, 1, 4), [422, 422, 422, 429])
        self.start_server()
        self.assertEqual(self.statuses(GENERATE, 1, 4), [422, 422, 422, 429])

    def test_default_handler_uses_the_documented_generation_limits(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(DEFAULT_API_CONFIG))
        server.RequestHandlerClass.log_message = lambda *args: None
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(lambda: (server.shutdown(), server.server_close(), thread.join(timeout=3)))
        self.port = server.server_port
        self.assertEqual(self.statuses(GENERATE, 1, 4), [422, 422, 422, 429])
        retry_after = int(self.send("POST", GENERATE, 1).headers["Retry-After"])
        self.assertTrue(3590 <= retry_after <= 3600, retry_after)


if __name__ == "__main__":
    unittest.main()
