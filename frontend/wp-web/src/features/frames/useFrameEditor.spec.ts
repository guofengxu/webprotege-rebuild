import { flushPromises } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { expect, test, vi } from "vitest";
import { queryKeys } from "@/shared/api/queryKeys";
import { ApiError } from "@/shared/api/problem";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import {
  cloneFrame,
  framesEqual,
  normalizeClassFrame,
  type ClassFrame,
} from "./frameModel";
import { useFrameEditor } from "./useFrameEditor";

type Editor = ReturnType<typeof useFrameEditor<ClassFrame>>;

function subject(name: string): ClassFrame["subject"] {
  return {
    iri: `http://ex.org#${name}`,
    entityType: "CLASS",
    displayName: name,
    deprecated: false,
    hasChildren: false,
  };
}

function frameOf(name: string, parents: ClassFrame["parents"] = []): ClassFrame {
  return {
    subject: subject(name),
    annotations: [],
    parents,
    propertyValues: [],
    otherAxioms: [],
  };
}

test("clean draft is replaced when the server cache updates", async () => {
  const { editor, queryClient } = await mountEditor({
    initial: frameOf("A"),
  });
  await flushPromises();

  expect(editor.draft.value?.subject?.displayName).toBe("A");

  const next = frameOf("B");
  queryClient.setQueryData(queryKeys.frame("p1", "CLASS", "http://ex.org#A"), next);
  await flushPromises();

  expect(editor.draft.value?.subject?.displayName).toBe("B");
  expect(editor.serverChanged.value).toBe(false);
  expect(editor.isDirty.value).toBe(false);
});

test("dirty draft is kept and serverChanged is set on server update", async () => {
  const { editor, queryClient } = await mountEditor({
    initial: frameOf("A"),
  });
  await flushPromises();

  editor.draft.value = frameOf("A-edited");
  await flushPromises();
  expect(editor.isDirty.value).toBe(true);

  queryClient.setQueryData(
    queryKeys.frame("p1", "CLASS", "http://ex.org#A"),
    frameOf("B"),
  );
  await flushPromises();

  expect(editor.draft.value?.subject?.displayName).toBe("A-edited");
  expect(editor.serverChanged.value).toBe(true);
});

test("save sends original server base as from and draft as to", async () => {
  const initial = frameOf("A");
  const save = vi.fn(async (body: { from: ClassFrame; to: ClassFrame }) => ({
    frame: body.to,
    revision: 2,
  }));
  const onSaved = vi.fn();
  const { editor } = await mountEditor({ initial, save, onSaved });
  await flushPromises();

  const loaded = editor.server.value;
  expect(loaded).toBeDefined();
  editor.draft.value = frameOf("A", [subject("Parent")!]);
  await editor.save();
  await flushPromises();

  expect(save).toHaveBeenCalledTimes(1);
  const body = save.mock.calls[0]?.[0];
  expect(body?.from).toStrictEqual(initial);
  expect(framesEqual(body?.from, loaded)).toBe(true);
  expect(body?.to.parents).toHaveLength(1);
  expect(onSaved).toHaveBeenCalledWith({
    frame: body?.to,
    revision: 2,
  });
});

test("after a clean server overwrite, save from is the newer server value", async () => {
  const save = vi.fn(async (body: { from: ClassFrame; to: ClassFrame }) => ({
    frame: body.to,
    revision: 3,
  }));
  const { editor, queryClient } = await mountEditor({
    initial: frameOf("A"),
    save,
  });
  await flushPromises();

  const next = frameOf("B");
  queryClient.setQueryData(queryKeys.frame("p1", "CLASS", "http://ex.org#A"), next);
  await flushPromises();

  editor.draft.value = {
    ...cloneFrame(next),
    otherAxioms: ["Class: B"],
  };
  await editor.save();
  await flushPromises();

  expect(save.mock.calls[0]?.[0].from).toStrictEqual(next);
});

test("successful save replaces the query cache", async () => {
  const saved = frameOf("Saved");
  const save = vi.fn(async () => ({ frame: saved, revision: 9 }));
  const { editor, queryClient } = await mountEditor({
    initial: frameOf("A"),
    save,
  });
  await flushPromises();

  editor.draft.value = frameOf("Edited");
  await editor.save();
  await flushPromises();

  expect(
    queryClient.getQueryData(queryKeys.frame("p1", "CLASS", "http://ex.org#A")),
  ).toStrictEqual(saved);
  expect(editor.draft.value).toStrictEqual(cloneFrame(saved));
  expect(editor.isDirty.value).toBe(false);
});

test("409 FRAME_CHANGED sets conflict and keeps the draft", async () => {
  const save = vi.fn(async () => {
    throw new ApiError(409, { status: 409, code: "FRAME_CHANGED" });
  });
  const { editor } = await mountEditor({ initial: frameOf("A"), save });
  await flushPromises();

  editor.draft.value = frameOf("Edited");
  await editor.save();
  await flushPromises();

  expect(editor.conflict.value).toBe(true);
  expect(editor.draft.value?.subject?.displayName).toBe("Edited");
  expect(editor.isDirty.value).toBe(true);
});

test("403 sets readOnly and keeps the draft", async () => {
  const save = vi.fn(async () => {
    throw new ApiError(403, { status: 403, code: "PERMISSION_DENIED" });
  });
  const { editor } = await mountEditor({ initial: frameOf("A"), save });
  await flushPromises();

  editor.draft.value = frameOf("Edited");
  await editor.save();
  await flushPromises();

  expect(editor.readOnly.value).toBe(true);
  expect(editor.draft.value?.subject?.displayName).toBe("Edited");
});

test("normalize fills draft arrays without marking dirty", async () => {
  const raw: ClassFrame = {
    subject: subject("A"),
  };
  const save = vi.fn(async (body: { from: ClassFrame; to: ClassFrame }) => ({
    frame: body.to,
    revision: 1,
  }));
  const { editor } = await mountEditor({
    initial: raw,
    save,
    normalize: normalizeClassFrame,
  });
  await flushPromises();

  expect(editor.draft.value?.annotations).toEqual([]);
  expect(editor.draft.value?.parents).toEqual([]);
  expect(editor.isDirty.value).toBe(false);

  editor.draft.value = {
    ...cloneFrame(editor.draft.value!),
    otherAxioms: ["Class: A"],
  };
  expect(editor.isDirty.value).toBe(true);

  await editor.save();
  await flushPromises();
  expect(save.mock.calls[0]?.[0].from).toStrictEqual(raw);
  expect(save.mock.calls[0]?.[0].from.annotations).toBeUndefined();
  expect(save.mock.calls[0]?.[0].to.otherAxioms).toEqual(["Class: A"]);
});

test("reloadFromServer discards the draft", async () => {
  let current = frameOf("A");
  const load = vi.fn(async () => current);
  const { editor } = await mountEditor({ initial: frameOf("A"), load });
  await flushPromises();

  editor.draft.value = frameOf("Edited");
  current = frameOf("Server");
  await editor.reloadFromServer();
  await flushPromises();

  expect(editor.draft.value?.subject?.displayName).toBe("Server");
  expect(editor.conflict.value).toBe(false);
  expect(editor.serverChanged.value).toBe(false);
  expect(editor.isDirty.value).toBe(false);
});

async function mountEditor(options: {
  initial: ClassFrame;
  load?: () => Promise<ClassFrame>;
  save?: (body: { from: ClassFrame; to: ClassFrame }) => Promise<{
    frame: ClassFrame;
    revision: number;
  }>;
  onSaved?: (result: { frame: ClassFrame; revision: number }) => void;
  normalize?: (frame: ClassFrame) => ClassFrame;
}) {
  const load = options.load ?? (async () => options.initial);
  const save =
    options.save ??
    (async (body) => ({
      frame: body.to,
      revision: 1,
    }));
  const onSaved = options.onSaved;
  const normalize = options.normalize;
  let editor!: Editor;

  const Probe = defineComponent({
    name: "FrameEditorProbe",
    setup() {
      editor = useFrameEditor<ClassFrame>({
        queryKey: queryKeys.frame("p1", "CLASS", "http://ex.org#A"),
        load,
        save,
        onSaved,
        normalize,
      });
      return () => h("div", editor.draft.value?.subject?.displayName ?? "pending");
    },
  });

  const rendered = await renderWithProviders(Probe);
  await flushPromises();
  return { ...rendered, editor };
}
