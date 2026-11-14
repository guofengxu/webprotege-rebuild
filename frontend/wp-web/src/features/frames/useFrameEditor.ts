import {
  useQuery,
  useQueryClient,
  type QueryKey,
} from "@tanstack/vue-query";
import {
  computed,
  readonly,
  ref,
  shallowRef,
  toRaw,
  toValue,
  watch,
  type ComputedRef,
  type MaybeRefOrGetter,
  type Ref,
} from "vue";
import {
  useProjectPermissions,
  type PermissionState,
} from "@/features/permissions/useProjectPermissions";
import { ApiError } from "@/shared/api/problem";
import { cloneFrame, framesEqual } from "./frameModel";

export type FrameSaveResult<F> = {
  frame: F;
  revision: number;
};

export type UseFrameEditorOptions<F> = {
  projectId: MaybeRefOrGetter<string>;
  queryKey: MaybeRefOrGetter<QueryKey>;
  load: () => Promise<F>;
  save: (body: { from: F; to: F }) => Promise<FrameSaveResult<F>>;
  onSaved?: (result: FrameSaveResult<F>) => void | Promise<void>;
  /** Applied only to the draft / dirty baseline; never to `from`. */
  normalize?: (frame: F) => F;
};

export type UseFrameEditorResult<F> = {
  server: Readonly<Ref<F | undefined>>;
  draft: Ref<F | undefined>;
  isDirty: ComputedRef<boolean>;
  isSaving: Ref<boolean>;
  conflict: Ref<boolean>;
  serverChanged: Ref<boolean>;
  readOnly: ComputedRef<boolean>;
  editPermission: ComputedRef<PermissionState>;
  save: () => Promise<void>;
  reset: () => void;
  reloadFromServer: () => Promise<void>;
};

export function useFrameEditor<F>(
  options: UseFrameEditorOptions<F>,
): UseFrameEditorResult<F> {
  const queryClient = useQueryClient();
  const resolvedKey = computed(() => toValue(options.queryKey));
  const normalize = options.normalize ?? ((frame: F) => frame);
  const { permission } = useProjectPermissions(() => toValue(options.projectId));
  const ontologyPermission = permission("EDIT_ONTOLOGY");
  const forbiddenByServer = ref(false);

  watch(
    ontologyPermission,
    (state) => {
      if (state === "allowed") {
        forbiddenByServer.value = false;
      }
    },
  );

  const editPermission = computed((): PermissionState => {
    if (forbiddenByServer.value) {
      return "denied";
    }
    return ontologyPermission.value;
  });

  const readOnly = computed(
    () =>
      editPermission.value === "denied" || editPermission.value === "pending",
  );

  const query = useQuery({
    queryKey: resolvedKey,
    queryFn: () => options.load(),
  });

  const base = shallowRef<F | undefined>();
  const cleanDraft = shallowRef<F | undefined>();
  const draft = ref<F | undefined>();
  const isSaving = ref(false);
  const conflict = ref(false);
  const serverChanged = ref(false);
  const applyingLocal = ref(false);

  const server = computed(() => query.data.value);
  const isDirty = computed(() => {
    if (draft.value === undefined || cleanDraft.value === undefined) {
      return false;
    }
    return !framesEqual(draft.value, cleanDraft.value);
  });

  watch(
    () => query.data.value,
    (data) => {
      if (data === undefined || applyingLocal.value) {
        return;
      }
      if (base.value === undefined || !isDirty.value) {
        applyServerFrame(data);
        serverChanged.value = false;
        return;
      }
      if (!framesEqual(data, base.value)) {
        serverChanged.value = true;
      }
    },
    { immediate: true },
  );

  function applyServerFrame(frame: F): void {
    base.value = frame;
    const normalized = normalize(cloneFrame(frame));
    cleanDraft.value = normalized;
    draft.value = cloneFrame(normalized);
  }

  function reset(): void {
    if (cleanDraft.value === undefined) {
      return;
    }
    draft.value = cloneFrame(cleanDraft.value);
  }

  async function reloadFromServer(): Promise<void> {
    const result = await query.refetch();
    const data = result.data;
    if (data === undefined) {
      return;
    }
    applyServerFrame(data);
    conflict.value = false;
    serverChanged.value = false;
  }

  async function save(): Promise<void> {
    if (
      base.value === undefined ||
      draft.value === undefined ||
      isSaving.value ||
      readOnly.value ||
      conflict.value
    ) {
      return;
    }
    isSaving.value = true;
    applyingLocal.value = true;
    try {
      const from = toRaw(base.value);
      const to = cloneFrame(toRaw(draft.value) as F);
      const result = await options.save({ from, to });
      queryClient.setQueryData(resolvedKey.value, result.frame);
      applyServerFrame(result.frame);
      conflict.value = false;
      serverChanged.value = false;
      await options.onSaved?.(result);
    } catch (error) {
      if (
        error instanceof ApiError &&
        error.status === 409 &&
        error.code === "FRAME_CHANGED"
      ) {
        conflict.value = true;
        return;
      }
      if (error instanceof ApiError && error.status === 403) {
        forbiddenByServer.value = true;
        return;
      }
      throw error;
    } finally {
      applyingLocal.value = false;
      isSaving.value = false;
    }
  }

  return {
    server: readonly(server) as Readonly<Ref<F | undefined>>,
    draft: draft as Ref<F | undefined>,
    isDirty,
    isSaving,
    conflict,
    serverChanged,
    readOnly,
    editPermission,
    save,
    reset,
    reloadFromServer,
  };
}
