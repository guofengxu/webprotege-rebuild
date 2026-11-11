export type SseMessage = {
  id?: string;
  event: string;
  data: string;
};

type Draft = {
  event: string;
  data: string[];
  id?: string;
  hasId: boolean;
};

function emptyDraft(): Draft {
  return { event: "", data: [], hasId: false };
}

function fieldValue(line: string): { name: string; value: string } {
  const colon = line.indexOf(":");
  if (colon === -1) {
    return { name: line, value: "" };
  }
  const raw = line.slice(colon + 1);
  return { name: line.slice(0, colon), value: raw.startsWith(" ") ? raw.slice(1) : raw };
}

function dispatch(draft: Draft, out: SseMessage[]): void {
  if (draft.data.length === 0) {
    return;
  }
  const message: SseMessage = {
    event: draft.event === "" ? "message" : draft.event,
    data: draft.data.join("\n"),
  };
  if (draft.hasId && draft.id !== undefined) {
    message.id = draft.id;
  }
  out.push(message);
}

function applyLine(draft: Draft, line: string, out: SseMessage[]): Draft {
  if (line === "") {
    dispatch(draft, out);
    return emptyDraft();
  }
  if (line.startsWith(":")) {
    const raw = line.slice(1);
    out.push({ event: "comment", data: raw.startsWith(" ") ? raw.slice(1) : raw });
    return draft;
  }
  const field = fieldValue(line);
  switch (field.name) {
    case "event":
      return { ...draft, event: field.value };
    case "data":
      return { ...draft, data: [...draft.data, field.value] };
    case "id":
      return field.value.includes("\0") ? draft : { ...draft, id: field.value, hasId: true };
    default:
      return draft;
  }
}

function takeCompleteLines(buffer: string, flush: boolean): { lines: string[]; rest: string } {
  const lines: string[] = [];
  let start = 0;
  let index = 0;
  while (index < buffer.length) {
    const current = buffer.charAt(index);
    if (current !== "\n" && current !== "\r") {
      index += 1;
      continue;
    }
    // A trailing CR may be the first half of a CRLF that arrives in the next chunk.
    if (current === "\r" && index === buffer.length - 1 && !flush) {
      return { lines, rest: buffer.slice(start) };
    }
    lines.push(buffer.slice(start, index));
    index += current === "\r" && buffer.charAt(index + 1) === "\n" ? 2 : 1;
    start = index;
  }
  return { lines, rest: buffer.slice(start) };
}

function withoutBom(buffer: string, pending: boolean): { buffer: string; pending: boolean } {
  if (!pending || buffer.length === 0) {
    return { buffer, pending };
  }
  return buffer.startsWith("\uFEFF")
    ? { buffer: buffer.slice(1), pending: false }
    : { buffer, pending: false };
}

async function* readEvents(reader: ReadableStreamDefaultReader<Uint8Array>): AsyncGenerator<SseMessage> {
  const decoder = new TextDecoder();
  let buffer = "";
  let draft = emptyDraft();
  let bomPending = true;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (value) {
        buffer += decoder.decode(value, { stream: true });
      }
      if (done) {
        buffer += decoder.decode();
      }
      const stripped = withoutBom(buffer, bomPending);
      buffer = stripped.buffer;
      bomPending = stripped.pending;
      const extracted = takeCompleteLines(buffer, done);
      buffer = extracted.rest;
      const produced: SseMessage[] = [];
      for (const line of extracted.lines) {
        draft = applyLine(draft, line, produced);
      }
      for (const message of produced) {
        yield message;
      }
      if (done) {
        return;
      }
    }
  } finally {
    releaseReader(reader);
  }
}

function releaseReader(reader: ReadableStreamDefaultReader<Uint8Array>): void {
  try {
    reader.releaseLock();
  } catch {
    // cancel() already released this reader, or a read is still settling.
  }
}

export function parseSse(stream: ReadableStream<Uint8Array>): AsyncGenerator<SseMessage> {
  const reader = stream.getReader();
  const generated = readEvents(reader);
  const finish = generated.return.bind(generated);
  generated.return = async (value) => {
    // Cancel first so a read() that is still pending can finish. return() before the
    // first next() never enters readEvents, so cancel() alone leaves the stream locked.
    await reader.cancel().catch(() => undefined);
    try {
      return await finish(value);
    } finally {
      releaseReader(reader);
    }
  };
  return generated;
}
