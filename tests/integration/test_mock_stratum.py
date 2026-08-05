#!/usr/bin/env python3
from __future__ import annotations
import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from mock_stratum_server import handle  # noqa: E402

async def exercise() -> None:
    server = await asyncio.start_server(lambda r, w: handle(r, w, "normal"), "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    async with server:
        reader, writer = await asyncio.open_connection("127.0.0.1", port)
        writer.write(b'{"id":1,"jsonrpc":"2.0","method":"login","params":{"login":"wallet","pass":"x"}}\n')
        await writer.drain()
        login = json.loads((await reader.readline()).decode())
        assert login["result"]["status"] == "OK"
        assert len(login["result"]["job"]["seed_hash"]) == 64
        writer.write(b'{"id":2,"jsonrpc":"2.0","method":"submit","params":{"id":"mock-session","job_id":"mock-job-1","nonce":"00000000","result":"00"}}\n')
        await writer.drain()
        submit = json.loads((await reader.readline()).decode())
        assert submit["result"]["accepted"] is True
        writer.close()
        await writer.wait_closed()
    print("Mock Stratum integration test: PASS")

if __name__ == "__main__":
    asyncio.run(exercise())
