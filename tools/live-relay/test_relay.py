import json
import unittest

from aiohttp import WSServerHandshakeError, web
from aiohttp.test_utils import TestClient, TestServer

from relay import Tickets, make_app, validate


class RelayUnitTest(unittest.TestCase):
    def test_tickets_are_one_use_and_expire(self):
        tickets = Tickets()
        a = tickets.mint(0)
        b = tickets.mint(5)
        self.assertTrue(tickets.consume(a, 10))
        self.assertFalse(tickets.consume(a, 11))
        self.assertFalse(tickets.consume(b, 125))

    def test_realtime_and_arbitrary_tool_config_dont_pass(self):
        with self.assertRaises(ValueError):
            validate('{"type":"session.update"}', True)
        with self.assertRaises(ValueError):
            validate('{"type":"response.create"}', True)
        with self.assertRaises(ValueError):
            validate('{"type":"session.input_audio.append","audio":"AA=="}', True)
        started = validate(json.dumps({"type": "session.start", "session": {
            "model": "unexpected-model", "store": True, "tools": [{}], "instructions": "Hello",
        }}), False)["session"]
        self.assertEqual(started["model"], "gpt-live-1.1")
        self.assertNotIn("tools", started)
        self.assertFalse(started["store"])
        self.assertEqual(started["delegation"], {"type": "client"})


class RelayTrafficTest(unittest.IsolatedAsyncioTestCase):
    async def test_auth_and_bidirectional_live_loop_against_fake_upstream(self):
        upstream_app = web.Application()
        events = []
        upstream_headers = []

        async def mock_openai(request):
            upstream_headers.append(request.headers["Authorization"])
            voice = web.WebSocketResponse()
            await voice.prepare(request)
            events.append(await voice.receive_json(timeout=2))
            await voice.send_json({"type": "session.started", "session": {"id": "test", "status": "active"}})
            events.append(await voice.receive_json(timeout=2))
            await voice.send_json({"type": "session.delegation.created",
                                   "delegation": {"id": "fixture", "target": "client"}, "offset_ms": 100})
            events.append(await voice.receive_json(timeout=2))
            await voice.send_json({"type": "session.output_audio.delta", "delta": "AAEC/w=="})
            events.append(await voice.receive_json(timeout=2))
            await voice.send_json({"type": "session.closed"})
            return voice

        upstream_app.router.add_get("/v1/live/sessions", mock_openai)
        async with TestServer(upstream_app) as upstream:
            url = str(upstream.make_url("/v1/live/sessions")).replace("http:", "ws:", 1)
            async with TestClient(TestServer(make_app("test-key", "X" * 48, url))) as phone:
                self.assertEqual((await phone.post("/_local/mint")).status, 401)
                with self.assertRaises(WSServerHandshakeError):
                    await phone.ws_connect("/live")
                response = await phone.post("/_local/mint", headers={"Authorization": "Bearer " + "X" * 48})
                ticket = (await response.json())["ticket"]
                self.assertEqual(len(ticket), 50)
                bearer = {"Authorization": "Bearer " + ticket}
                async with phone.ws_connect("/live", headers=bearer) as voice:
                    await voice.send_json({"type": "session.start", "session": {"instructions": "Test"}})
                    self.assertEqual((await voice.receive_json(timeout=2))["type"], "session.started")
                    await voice.send_json({"type": "session.input_audio.append", "audio": "AAEC/w=="})
                    self.assertEqual((await voice.receive_json(timeout=2))["delegation"]["id"], "fixture")
                    await voice.send_json({"type": "session.commentary.append", "content": "test reply",
                                           "delegation_id": "fixture"})
                    self.assertEqual((await voice.receive_json(timeout=2))["delta"], "AAEC/w==")
                    await voice.send_json({"type": "session.close"})
                    self.assertEqual((await voice.receive_json(timeout=2))["type"], "session.closed")
                with self.assertRaises(WSServerHandshakeError):
                    await phone.ws_connect("/live", headers=bearer)
        self.assertEqual(upstream_headers, ["Bearer test-key"])
        self.assertEqual(events[2]["delegation_id"], "fixture")
        self.assertNotIn("pocket_", json.dumps(events))


if __name__ == "__main__":
    unittest.main()
