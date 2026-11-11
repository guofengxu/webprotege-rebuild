import { expect, test } from "vitest";
import { parseSse, type SseMessage } from "./sseParser";

function byteStream(chunks: readonly string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk));
      }
      controller.close();
    },
  });
}

function frame(lines: readonly string[], newline = "\n"): string {
  return [...lines, "", ""].join(newline);
}

async function readAll(chunks: readonly string[]): Promise<SseMessage[]> {
  const messages: SseMessage[] = [];
  for await (const message of parseSse(byteStream(chunks))) {
    messages.push(message);
  }
  return messages;
}

async function readBytes(chunks: readonly Uint8Array[]): Promise<SseMessage[]> {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(chunk);
      }
      controller.close();
    },
  });
  const messages: SseMessage[] = [];
  for await (const message of parseSse(stream)) {
    messages.push(message);
  }
  return messages;
}

test("joins data lines and keeps id and event, stripping one leading space", async () => {
  const messages = await readAll([
    frame([
      "id: 12",
      "event: ProjectChanged",
      `data: ${JSON.stringify({ type: "ProjectChanged" })}`,
      `data: ${JSON.stringify({ revNo: 12 })}`,
    ]),
  ]);

  expect(messages).toEqual([
    {
      id: "12",
      event: "ProjectChanged",
      data: `${JSON.stringify({ type: "ProjectChanged" })}\n${JSON.stringify({ revNo: 12 })}`,
    },
  ]);
});

test("yields each comment line and still parses the event around it", async () => {
  const messages = await readAll([
    [
      ":heartbeat",
      "",
      "id: 4",
      ":ping",
      "event: PermissionsChanged",
      `data: ${JSON.stringify({ type: "PermissionsChanged" })}`,
      "",
      "",
    ].join("\n"),
  ]);

  expect(messages).toEqual([
    { event: "comment", data: "heartbeat" },
    { event: "comment", data: "ping" },
    {
      id: "4",
      event: "PermissionsChanged",
      data: JSON.stringify({ type: "PermissionsChanged" }),
    },
  ]);
});

test("parses a resync event that has no id", async () => {
  const messages = await readAll([
    frame(["event: resync", `data: ${JSON.stringify({ type: "resync" })}`]),
  ]);

  expect(messages).toEqual([
    { event: "resync", data: JSON.stringify({ type: "resync" }) },
  ]);
});

test("reassembles one event split across chunks and across a CRLF", async () => {
  const raw = frame(
    [
      "id:9",
      "event: EntityFrameChanged",
      `data: ${JSON.stringify({ iri: "http://example.org/A" })}`,
    ],
    "\r\n",
  );
  const nameSplit = raw.indexOf("FrameChanged");
  const lastBreak = raw.lastIndexOf("\r\n");
  const messages = await readAll([
    raw.slice(0, nameSplit),
    raw.slice(nameSplit, lastBreak + 1),
    raw.slice(lastBreak + 1),
  ]);

  expect(messages).toEqual([
    {
      id: "9",
      event: "EntityFrameChanged",
      data: JSON.stringify({ iri: "http://example.org/A" }),
    },
  ]);
});

test("keeps a CRLF together when the carriage return and line feed arrive in different chunks", async () => {
  const raw = ["id:9", "event: EntityFrameChanged", "data: hello", "data: world", "", ""].join("\r\n");
  const split = raw.indexOf("hello\r\n") + "hello\r".length;
  const messages = await readAll([raw.slice(0, split), raw.slice(split)]);
  expect(messages).toEqual([{ id: "9", event: "EntityFrameChanged", data: "hello\nworld" }]);
});

test("treats a final carriage return as a line ending when the stream ends", async () => {
  const crFrame = ["id: 1", "event: resync", `data: ${JSON.stringify({ type: "resync" })}`, "", ""].join(
    "\r",
  );
  const lfThenCr = `event: resync\ndata: ${JSON.stringify({ type: "resync" })}\n\r`;
  expect(await readAll(["data: one\r\n\r\ndata: two\r\n\r"])).toEqual([
    { event: "message", data: "one" },
    { event: "message", data: "two" },
  ]);
  expect(await readAll([crFrame])).toEqual([
    { id: "1", event: "resync", data: JSON.stringify({ type: "resync" }) },
  ]);
  expect(await readAll([lfThenCr])).toEqual([
    { event: "resync", data: JSON.stringify({ type: "resync" }) },
  ]);
});

test("unlocks the stream when iteration is closed before the first read", async () => {
  const stream = byteStream([frame(["data: one"])]);
  const messages = parseSse(stream);
  await expect(messages.return(undefined)).resolves.toEqual({ done: true, value: undefined });
  expect(stream.locked).toBe(false);
});

test("releases the stream when iteration stops before the end", async () => {
  const stream = byteStream([frame(["data: one"]), frame(["data: two"])]);
  const messages = parseSse(stream);
  await expect(messages.next()).resolves.toEqual({
    done: false,
    value: { event: "message", data: "one" },
  });
  await expect(messages.return(undefined)).resolves.toEqual({ done: true, value: undefined });
  expect(stream.locked).toBe(false);
});

test("cancels a read that is still waiting when iteration stops", async () => {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(frame(["data: one"])));
    },
  });
  const messages = parseSse(stream);
  await expect(messages.next()).resolves.toEqual({
    done: false,
    value: { event: "message", data: "one" },
  });

  const pending = messages.next();
  const stopped = messages.return(undefined);
  await expect(stopped).resolves.toEqual({ done: true, value: undefined });
  await expect(pending).resolves.toMatchObject({ done: true });
  expect(stream.locked).toBe(false);
});

test("strips one leading space from a comment and ignores retry", async () => {
  const messages = await readAll([
    [": heartbeat", "retry: 3000", "event: resync", `data: ${JSON.stringify({ type: "resync" })}`, "", ""].join(
      "\n",
    ),
  ]);
  expect(messages).toEqual([
    { event: "comment", data: "heartbeat" },
    { event: "resync", data: JSON.stringify({ type: "resync" }) },
  ]);
});

test("drops an id that contains NUL and a fragment with no line break", async () => {
  const withNul = await readAll([
    frame(["id: 1\u00002", "event: resync", `data: ${JSON.stringify({ type: "resync" })}`]),
  ]);
  expect(withNul).toEqual([{ event: "resync", data: JSON.stringify({ type: "resync" }) }]);
  expect(withNul[0]).not.toHaveProperty("id");
  expect(await readAll(["data: hello"])).toEqual([]);
});

test("reassembles a payload split across chunks, including a multibyte character", async () => {
  const payload = JSON.stringify({ displayName: "子类" });
  const raw = frame([`data: ${payload}`]);
  const bytes = new TextEncoder().encode(raw);
  const character = new TextEncoder().encode("子");
  const splitAt = bytes.findIndex((byte, index) => byte === character[0] && index >= 0);
  expect(splitAt).toBeGreaterThan(0);
  const messages = await readBytes([bytes.slice(0, splitAt + 1), bytes.slice(splitAt + 1)]);
  expect(messages).toEqual([{ event: "message", data: payload }]);
});

test("ignores a UTF-8 BOM before the first field", async () => {
  const body = frame(["id: 3", "event: resync", `data: ${JSON.stringify({ type: "resync" })}`]);
  const bom = new Uint8Array([0xef, 0xbb, 0xbf]);
  const rest = new TextEncoder().encode(body);
  const messages = await readBytes([bom.slice(0, 1), new Uint8Array([...bom.slice(1), ...rest])]);
  expect(messages).toEqual([{ id: "3", event: "resync", data: JSON.stringify({ type: "resync" }) }]);
});
