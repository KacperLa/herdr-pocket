"""A private, single-call Pocket GPT-Live relay. Publish only GET /live over TLS."""
import argparse
import asyncio
import base64
import hmac
import json
import os
import secrets
import urllib.request

from aiohttp import ClientSession, ClientTimeout, WSMsgType, web

OPENAI_URL = "wss://api.openai.com/v1/live/sessions"
LIMIT = 256 * 1024
TICKET_AGE = 120
CALL_LIMIT = 600


class Tickets:
    def __init__(self):
        self.pending = {}

    def expire(self, now):
        self.pending = {ticket: expiry for ticket, expiry in self.pending.items() if expiry > now}

    def mint(self, now):
        self.expire(now)
        if len(self.pending) >= 8:
            return None
        ticket = "pocket_" + secrets.token_urlsafe(32)
        self.pending[ticket] = now + TICKET_AGE
        return ticket

    def consume(self, ticket, now):
        self.expire(now)
        return self.pending.pop(ticket, None) is not None


def validate(message, started):
    value = json.loads(message)
    if not isinstance(value, dict):
        raise ValueError("Message must be an object")
    kind = value.get("type")
    if not started:
        session = value.get("session")
        if kind != "session.start" or not isinstance(session, dict):
            raise ValueError("Expected session.start")
        instructions = session.get("instructions")
        if not isinstance(instructions, str) or len(instructions.encode()) > 4000:
            raise ValueError("Invalid voice prompt")
        # Do not forward arbitrary model, tool, recording or backend config.
        return {
            "type": "session.start", "event_id": "pocket-live-start",
            "session": {"model": "gpt-live-1.1", "store": False, "instructions": instructions,
                        "audio": {"format": {"type": "audio/pcm", "rate": 24000},
                                  "output": {"voice": "marin"}},
                        "delegation": {"type": "client"}},
        }
    if kind == "session.close":
        return {"type": kind}
    if kind == "session.input_audio.append":
        audio = value.get("audio")
        if not isinstance(audio, str) or not 4 <= len(audio) <= 22000:
            raise ValueError("Invalid audio")
        pcm = base64.b64decode(audio, validate=True)
        if len(pcm) < 2 or len(pcm) > 16384 or len(pcm) % 2:
            raise ValueError("Invalid PCM16 payload")
        return {"type": kind, "audio": audio}
    if kind == "session.commentary.append":
        ident, content = value.get("delegation_id"), value.get("content")
        if not isinstance(ident, str) or not 1 <= len(ident) <= 512 or not ident.isascii():
            raise ValueError("Invalid delegation ID")
        if not isinstance(content, str) or not 1 <= len(content.encode()) <= 475:
            raise ValueError("Invalid commentary")
        return {"type": kind, "delegation_id": ident, "content": content}
    raise ValueError("Unsupported Pocket event")


def make_app(key, operator, upstream_url=OPENAI_URL):
    if not key or len(operator) < 32 or not operator.isascii():
        raise ValueError("Set the OpenAI project key and a strong relay operator secret")
    tickets = Tickets()
    in_call = False
    now = lambda: asyncio.get_running_loop().time()
    app = web.Application(client_max_size=16384)

    async def mint(req):
        # The example reverse proxy publishes /live only. Requires the operator
        # secret independently even if someone exposes this route by mistake.
        expected = "Bearer " + operator
        supplied = req.headers.get("Authorization", "")
        if not supplied.isascii() or not hmac.compare_digest(supplied, expected):
            raise web.HTTPUnauthorized()
        ticket = tickets.mint(now())
        if not ticket:
            raise web.HTTPTooManyRequests()
        return web.json_response({"ticket": ticket, "expires_in": TICKET_AGE},
                                 headers={"Cache-Control": "no-store"})

    async def live(req):
        nonlocal in_call
        # The Android client sends no Origin. Do not accept browser-page clients.
        if "Origin" in req.headers or req.query_string:
            raise web.HTTPForbidden()
        authorization = req.headers.get("Authorization", "")
        if not authorization.startswith("Bearer "):
            raise web.HTTPUnauthorized()
        if in_call:
            raise web.HTTPServiceUnavailable()
        ws = web.WebSocketResponse(heartbeat=22, max_msg_size=LIMIT, compress=False)
        if not ws.can_prepare(req).ok:
            raise web.HTTPBadRequest()
        if not tickets.consume(authorization[7:], now()):
            raise web.HTTPUnauthorized()
        in_call = True
        try:
            await ws.prepare(req)
            async with asyncio.timeout(CALL_LIMIT):
                first = await ws.receive(timeout=12)
                if first.type != WSMsgType.TEXT:
                    raise ValueError("Expected session start")
                start = validate(first.data, False)
                async with ClientSession(timeout=ClientTimeout(total=None, sock_connect=10),
                                         trust_env=False) as client:
                    async with asyncio.timeout(12):
                        upstream_socket = await client.ws_connect(
                            upstream_url, headers={"Authorization": "Bearer " + key},
                            max_msg_size=LIMIT, heartbeat=25, compress=0)
                    async with upstream_socket as upstream:
                        await upstream.send_json(start)

                        async def to_upstream():
                            async for packet in ws:
                                if packet.type != WSMsgType.TEXT:
                                    raise ValueError("Unsupported frame")
                                event = validate(packet.data, True)
                                await upstream.send_json(event)
                                if event["type"] == "session.close":
                                    # Wait for upstream session.closed, but cap finalization.
                                    await asyncio.sleep(5)
                                    return

                        async def to_phone():
                            async for packet in upstream:
                                if packet.type != WSMsgType.TEXT:
                                    raise ValueError("Invalid upstream frame")
                                await ws.send_str(packet.data)
                                if json.loads(packet.data).get("type") == "session.closed":
                                    return

                        jobs = [asyncio.create_task(to_upstream()), asyncio.create_task(to_phone())]
                        try:
                            done, _ = await asyncio.wait(jobs, return_when=asyncio.FIRST_COMPLETED)
                            for job in done:
                                job.result()
                        finally:
                            for job in jobs:
                                job.cancel()
                            await asyncio.gather(*jobs, return_exceptions=True)
        except Exception:
            # Send no raw remote errors, headers, transcript, keys, or audio to logs.
            if ws.prepared and not ws.closed:
                try:
                    await ws.send_json({"type": "error", "error": {"code": "pocket_relay_ended"}})
                except ConnectionError:
                    pass
        finally:
            await ws.close()
            in_call = False
        return ws

    app.router.add_post("/_local/mint", mint)
    app.router.add_get("/live", live, allow_head=False)
    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["serve", "mint"])
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    operator = os.environ.get("POCKET_LIVE_OPERATOR_SECRET", "")
    if len(operator) < 32:
        parser.error("Set POCKET_LIVE_OPERATOR_SECRET in your private environment")
    if args.command == "mint":
        request = urllib.request.Request(f"http://127.0.0.1:{args.port}/_local/mint",
                                         method="POST", headers={"Authorization": "Bearer " + operator})
        with urllib.request.urlopen(request, timeout=4) as response:
            print(json.load(response)["ticket"])
    else:
        key = os.environ.get("OPENAI_API_KEY")
        if not key:
            parser.error("Set OPENAI_API_KEY on this trusted machine")
        web.run_app(make_app(key, operator), host="127.0.0.1", port=args.port,
                    access_log=None, print=None)


if __name__ == "__main__":
    main()
