#!/usr/bin/env python3
"""Bounded local Monero-style Stratum mock for integration and failure-path tests."""
from __future__ import annotations
import argparse
import asyncio
import json
import ssl
from pathlib import Path

JOB = {
    "job_id": "mock-job-1",
    "blob": "00" * 39 + "00000000" + "00" * 37,
    "target": "ffffffff",
    "seed_hash": "11" * 32,
    "height": 123456,
    "nonce_offset": 39,
}

async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, mode: str) -> None:
    try:
        while True:
            raw = await asyncio.wait_for(reader.readline(), timeout=15)
            if not raw:
                return
            request = json.loads(raw.decode("utf-8"))
            request_id = request.get("id")
            method = request.get("method")
            if mode == "drop":
                return
            if mode == "malformed":
                writer.write(b"{not-json}\n")
                await writer.drain()
                return
            if method == "login":
                if mode == "reject-login":
                    response = {"id": request_id, "jsonrpc": "2.0", "error": {"message": "mock login rejection"}}
                else:
                    response = {"id": request_id, "jsonrpc": "2.0", "result": {"id": "mock-session", "status": "OK", "job": JOB}}
            elif method == "submit":
                accepted = mode != "reject-share"
                response = {"id": request_id, "jsonrpc": "2.0", "result": {"status": "OK" if accepted else "LOW DIFFICULTY", "accepted": accepted}}
            elif method in {"keepalive", "keepalived"}:
                response = {"id": request_id, "jsonrpc": "2.0", "result": {"status": "KEEPALIVED"}}
            else:
                response = {"id": request_id, "jsonrpc": "2.0", "error": {"message": "unknown method"}}
            if mode == "delay":
                await asyncio.sleep(1.5)
            writer.write((json.dumps(response, separators=(",", ":")) + "\n").encode())
            await writer.drain()
    except (asyncio.TimeoutError, json.JSONDecodeError, ConnectionError):
        return
    finally:
        writer.close()
        await writer.wait_closed()

async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=33333)
    parser.add_argument("--mode", choices=["normal", "reject-login", "reject-share", "malformed", "drop", "delay"], default="normal")
    parser.add_argument("--cert", type=Path)
    parser.add_argument("--key", type=Path)
    args = parser.parse_args()
    context = None
    if args.cert or args.key:
        if not args.cert or not args.key:
            parser.error("--cert and --key must be provided together")
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(args.cert, args.key)
    server = await asyncio.start_server(lambda r, w: handle(r, w, args.mode), args.host, args.port, ssl=context)
    sockets = ", ".join(str(sock.getsockname()) for sock in server.sockets or [])
    print(f"mock stratum listening on {sockets}; mode={args.mode}; tls={context is not None}", flush=True)
    async with server:
        await server.serve_forever()

if __name__ == "__main__":
    asyncio.run(main())
