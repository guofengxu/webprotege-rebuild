<script setup lang="ts">
import type { components, MemberResponse } from "@wp/api-types";
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, nextTick, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useCurrentUser } from "@/features/auth/useAuth";
import { ApiError, problemMessage } from "@/shared/api/problem";
import {
  useDeleteMember,
  useMemberCandidates,
  useMembers,
  usePutMember,
} from "./api";

type UserSummaryResponse = components["schemas"]["UserSummaryResponse"];

export type ProjectRole =
  | "CAN_VIEW"
  | "CAN_COMMENT"
  | "CAN_EDIT"
  | "CAN_MANAGE";

type CandidateSuggestion = {
  readonly value: string;
  readonly userId: string;
  readonly displayName: string;
  readonly alreadyMember: boolean;
  readonly disabled: boolean;
  readonly isError: boolean;
  readonly label: string;
};

type CandidateWaitOutcome = "ready" | "superseded" | "cleared";

const PROJECT_ROLES: readonly ProjectRole[] = [
  "CAN_VIEW",
  "CAN_COMMENT",
  "CAN_EDIT",
  "CAN_MANAGE",
] as const;

const props = defineProps<{
  projectId: string;
}>();

const emit = defineEmits<{
  "update:dirty": [value: boolean];
}>();

const { t } = useI18n();
const currentUser = useCurrentUser();
const membersQuery = useMembers(() => props.projectId);
const putMember = usePutMember(() => props.projectId);
const deleteMember = useDeleteMember(() => props.projectId);

const addUserId = ref("");
const addRole = ref<ProjectRole>("CAN_VIEW");
const addError = ref("");
const adding = ref(false);
const roleDrafts = reactive<Record<string, ProjectRole>>({});
const busyUsers = reactive<Record<string, boolean>>({});
let removeLock = false;

const candidatesQuery = useMemberCandidates(() => props.projectId, addUserId);

onMounted(() => {
  emit("update:dirty", false);
});

const rows = computed(() => membersQuery.data.value ?? []);

const forbidden = computed(
  () =>
    membersQuery.isError.value &&
    membersQuery.error.value instanceof ApiError &&
    membersQuery.error.value.status === 403,
);

const loadError = computed(() => {
  if (!membersQuery.isError.value || forbidden.value) {
    return "";
  }
  return problemMessage(membersQuery.error.value, t);
});

const currentUserId = computed(() => currentUser.data.value?.userId ?? "");

const candidateStatus = computed(() => {
  if (
    candidatesQuery.isError.value &&
    candidatesQuery.debouncedQuery.value.length > 0 &&
    addUserId.value.trim().length > 0
  ) {
    return t("members.candidateSearchError");
  }
  return "";
});

const addDescribedBy = computed(() => {
  const parts: string[] = [];
  if (candidateStatus.value !== "") {
    parts.push("members-candidate-status");
  }
  if (addError.value !== "") {
    parts.push("members-add-error");
  }
  return parts.length > 0 ? parts.join(" ") : undefined;
});

watch(
  rows,
  (members) => {
    syncRoleDrafts(members);
  },
  { immediate: true },
);

function syncRoleDrafts(members: readonly MemberResponse[]): void {
  const seen = new Set<string>();
  for (const member of members) {
    const userId = member.userId?.trim() ?? "";
    if (userId.length === 0) {
      continue;
    }
    seen.add(userId);
    const role = normalizeRole(member.role);
    if (busyUsers[userId] === true) {
      continue;
    }
    roleDrafts[userId] = role;
  }
  for (const key of Object.keys(roleDrafts)) {
    if (!seen.has(key)) {
      delete roleDrafts[key];
    }
  }
}

function normalizeRole(value: string | undefined): ProjectRole {
  if (
    value === "CAN_VIEW" ||
    value === "CAN_COMMENT" ||
    value === "CAN_EDIT" ||
    value === "CAN_MANAGE"
  ) {
    return value;
  }
  return "CAN_VIEW";
}

function roleOptionLabel(role: ProjectRole): string {
  return `${roleLabel(role)} — ${roleDescription(role)}`;
}

function roleLabel(role: ProjectRole): string {
  return t(`members.roles.${role}.name`);
}

function roleDescription(role: ProjectRole): string {
  return t(`members.roles.${role}.description`);
}

function isExistingMember(userId: string): boolean {
  return rows.value.some((member) => member.userId === userId);
}

function memberRole(userId: string): ProjectRole {
  const fromServer = rows.value.find((member) => member.userId === userId)?.role;
  return roleDrafts[userId] ?? normalizeRole(fromServer);
}

function formatCandidateLabel(user: UserSummaryResponse): string {
  const userId = user.userId?.trim() ?? "";
  const displayName = user.displayName?.trim() ?? "";
  if (displayName.length === 0) {
    return userId;
  }
  return `${displayName}（${userId}）`;
}

function buildSuggestions(): CandidateSuggestion[] {
  const typed = addUserId.value;
  if (candidatesQuery.isError.value) {
    return [
      {
        value: typed,
        userId: "",
        displayName: "",
        alreadyMember: false,
        disabled: true,
        isError: true,
        label: t("members.candidateSearchError"),
      },
    ];
  }
  const hits = candidatesQuery.data.value ?? [];
  return hits.map((user) => {
    const userId = user.userId?.trim() ?? "";
    const displayName = user.displayName?.trim() ?? "";
    const alreadyMember = userId.length > 0 && isExistingMember(userId);
    return {
      // Keep the typed query as value so selecting a disabled row does not
      // overwrite the input with a real userId (Element Plus always applies valueKey).
      value: alreadyMember ? typed : userId,
      userId,
      displayName,
      alreadyMember,
      disabled: alreadyMember,
      isError: false,
      label: formatCandidateLabel(user),
    };
  });
}

function whenCandidatesReady(
  expected: string,
): Promise<CandidateWaitOutcome> {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (outcome: CandidateWaitOutcome): void => {
      if (settled) {
        return;
      }
      settled = true;
      stop();
      resolve(outcome);
    };
    const stop = watch(
      [
        () => addUserId.value,
        () => candidatesQuery.debouncedQuery.value,
        () => candidatesQuery.isFetching.value,
        () => candidatesQuery.status.value,
      ],
      () => {
        const current = addUserId.value.trim();
        if (current.length === 0) {
          finish("cleared");
          return;
        }
        if (current !== expected) {
          finish("superseded");
          return;
        }
        if (candidatesQuery.debouncedQuery.value !== expected) {
          return;
        }
        if (candidatesQuery.isFetching.value) {
          return;
        }
        const status = candidatesQuery.status.value;
        if (status === "success" || status === "error") {
          finish("ready");
        }
      },
      { flush: "post", immediate: true },
    );
  });
}

function markDisabledOptions(): void {
  for (const li of document.querySelectorAll<HTMLElement>(
    ".el-autocomplete-suggestion li[role='option'], .el-autocomplete__popper li[role='option']",
  )) {
    const disabled = li.querySelector("[data-disabled='true']") !== null;
    if (disabled) {
      li.setAttribute("aria-disabled", "true");
    } else {
      li.removeAttribute("aria-disabled");
    }
  }
}

async function fetchSuggestions(
  queryString: string,
): Promise<CandidateSuggestion[] | void> {
  const trimmed = queryString.trim();
  if (trimmed.length === 0) {
    return [];
  }
  const outcome = await whenCandidatesReady(trimmed);
  if (outcome === "superseded") {
    // Skip cb so a newer in-flight fetch keeps ownership of loading/results.
    return;
  }
  if (outcome === "cleared") {
    return [];
  }
  const items = buildSuggestions();
  void nextTick(() => {
    markDisabledOptions();
  });
  return items;
}

function onCandidateSelect(item: Record<string, unknown>): void {
  const disabled = item.disabled === true;
  const alreadyMember = item.alreadyMember === true;
  const isError = item.isError === true;
  const userId = typeof item.userId === "string" ? item.userId : "";
  if (disabled || alreadyMember || isError || userId.length === 0) {
    return;
  }
  addUserId.value = userId;
}

async function onAddMember(): Promise<void> {
  if (adding.value || putMember.isPending.value) {
    return;
  }
  adding.value = true;
  addError.value = "";
  try {
    const userId = addUserId.value.trim();
    if (userId.length === 0) {
      addError.value = t("members.userIdRequired");
      return;
    }
    if (isExistingMember(userId)) {
      addError.value = t("members.alreadyMember");
      return;
    }
    try {
      await putMember.mutateAsync({
        userId,
        body: { role: addRole.value },
      });
      addUserId.value = "";
      addRole.value = "CAN_VIEW";
      ElMessage.success(t("members.addSuccess"));
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        addError.value = t("members.userNotFound");
        return;
      }
      addError.value = memberErrorMessage(error);
    }
  } finally {
    adding.value = false;
    emit("update:dirty", false);
  }
}

async function onRoleChange(userId: string, rawNext: string): Promise<void> {
  const nextRole = normalizeRole(rawNext);
  const previous = normalizeRole(
    rows.value.find((member) => member.userId === userId)?.role,
  );
  if (nextRole === previous || busyUsers[userId] === true) {
    roleDrafts[userId] = previous;
    return;
  }
  roleDrafts[userId] = nextRole;
  busyUsers[userId] = true;

  if (
    userId === currentUserId.value &&
    previous === "CAN_MANAGE" &&
    nextRole !== "CAN_MANAGE"
  ) {
    const confirmed = await confirmSelfManage(
      t("members.selfManageRoleMessage"),
    );
    if (!confirmed) {
      roleDrafts[userId] = previous;
      busyUsers[userId] = false;
      return;
    }
  }

  try {
    await putMember.mutateAsync({
      userId,
      body: { role: nextRole },
    });
    ElMessage.success(t("members.roleUpdated"));
  } catch (error) {
    roleDrafts[userId] = previous;
    ElMessage.error(memberErrorMessage(error));
  } finally {
    busyUsers[userId] = false;
    emit("update:dirty", false);
  }
}

async function onRemove(member: MemberResponse): Promise<void> {
  const userId = member.userId?.trim() ?? "";
  if (userId.length === 0 || removeLock || deleteMember.isPending.value) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("members.removeMessage", { userId }),
      t("members.removeTitle"),
      {
        type: "warning",
        confirmButtonText: t("members.removeConfirm"),
        cancelButtonText: t("members.cancel"),
      },
    );
  } catch {
    return;
  }

  const wasManager = normalizeRole(member.role) === "CAN_MANAGE";
  if (userId === currentUserId.value && wasManager) {
    const confirmed = await confirmSelfManage(
      t("members.selfManageRemoveMessage"),
    );
    if (!confirmed) {
      return;
    }
  }

  removeLock = true;
  busyUsers[userId] = true;
  try {
    await deleteMember.mutateAsync(userId);
    ElMessage.success(t("members.removeSuccess"));
  } catch (error) {
    ElMessage.error(memberErrorMessage(error));
  } finally {
    busyUsers[userId] = false;
    removeLock = false;
    emit("update:dirty", false);
  }
}

async function confirmSelfManage(message: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, t("members.selfManageTitle"), {
      type: "warning",
      confirmButtonText: t("members.selfManageConfirm"),
      cancelButtonText: t("members.cancel"),
    });
    return true;
  } catch {
    return false;
  }
}

function memberErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.code === "LAST_MANAGER") {
    return t("members.lastManager");
  }
  return problemMessage(error, t);
}
</script>

<template>
  <div class="members-tab">
    <el-alert
      v-if="forbidden"
      type="error"
      show-icon
      :closable="false"
      :title="t('members.forbidden')"
    />

    <template v-else>
      <el-alert
        v-if="loadError"
        type="error"
        show-icon
        :closable="false"
        :title="t('members.loadError')"
        :description="loadError"
      >
        <el-button
          type="primary"
          class="members-tab__hit"
          @click="membersQuery.refetch()"
        >
          {{ t("members.retry") }}
        </el-button>
      </el-alert>

      <template v-else>
        <div
          v-if="membersQuery.isPending.value && rows.length === 0"
          role="status"
          aria-live="polite"
        >
          {{ t("members.loading") }}
        </div>

        <template v-else>
          <section
            class="members-tab__add"
            :aria-label="t('members.addMember')"
          >
            <p
              id="members-candidate-status"
              class="sr-only"
              role="status"
              aria-live="polite"
            >
              {{ candidateStatus }}
            </p>
            <form
              class="members-tab__add-row"
              @submit.prevent="onAddMember"
            >
              <label class="members-tab__field">
                <span class="sr-only">{{ t("members.userSearchPlaceholder") }}</span>
                <el-autocomplete
                  v-model="addUserId"
                  name="userId"
                  data-testid="add-user-id"
                  value-key="value"
                  :debounce="0"
                  :trigger-on-focus="false"
                  :fetch-suggestions="fetchSuggestions"
                  :placeholder="t('members.userSearchPlaceholder')"
                  :disabled="adding"
                  :aria-invalid="addError !== ''"
                  :aria-describedby="addDescribedBy"
                  @select="onCandidateSelect"
                >
                  <template #default="{ item }">
                    <div
                      class="members-tab__suggestion"
                      :class="{ 'is-disabled': item.disabled }"
                      :aria-disabled="item.disabled ? 'true' : undefined"
                      :data-disabled="item.disabled ? 'true' : undefined"
                    >
                      <span>{{ item.label }}</span>
                      <span
                        v-if="item.alreadyMember"
                        class="members-tab__suggestion-badge"
                      >
                        {{ t("members.alreadyMemberBadge") }}
                      </span>
                    </div>
                  </template>
                </el-autocomplete>
              </label>
              <label class="members-tab__field members-tab__field--role">
                <span class="sr-only">{{ t("members.role") }}</span>
                <el-select
                  v-model="addRole"
                  :disabled="adding"
                >
                  <el-option
                    v-for="role in PROJECT_ROLES"
                    :key="role"
                    :label="roleOptionLabel(role)"
                    :value="role"
                  />
                </el-select>
              </label>
              <el-button
                type="primary"
                native-type="submit"
                class="members-tab__hit"
                :loading="adding"
                :disabled="adding"
              >
                {{ t("members.addMember") }}
              </el-button>
            </form>
            <p
              v-if="addError"
              id="members-add-error"
              class="members-tab__error"
              role="alert"
            >
              {{ addError }}
            </p>
          </section>

          <section
            class="members-tab__list"
            aria-labelledby="members-table-heading"
            :aria-busy="membersQuery.isFetching.value ? 'true' : 'false'"
          >
            <h2
              id="members-table-heading"
              class="members-tab__heading"
            >
              {{ t("members.tableLabel") }}
            </h2>
            <el-table
              :data="rows"
              row-key="userId"
              stripe
              :empty-text="t('members.empty')"
            >
              <el-table-column
                :label="t('members.userId')"
                min-width="160"
              >
                <template #default="{ row }">
                  <span :data-user-id="row.userId">{{ row.userId ?? "" }}</span>
                </template>
              </el-table-column>
              <el-table-column
                :label="t('members.role')"
                min-width="220"
              >
                <template #default="{ row }">
                  <el-select
                    :model-value="memberRole(row.userId ?? '')"
                    :data-testid="`member-role-${row.userId ?? ''}`"
                    :disabled="busyUsers[row.userId ?? ''] === true"
                    :aria-label="`${t('members.role')} ${row.userId ?? ''}`"
                    @change="
                      (value: string) => onRoleChange(row.userId ?? '', value)
                    "
                  >
                    <el-option
                      v-for="role in PROJECT_ROLES"
                      :key="role"
                      :label="roleOptionLabel(role)"
                      :value="role"
                    />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column
                :label="t('members.actions')"
                min-width="120"
              >
                <template #default="{ row }">
                  <el-button
                    type="danger"
                    plain
                    class="members-tab__hit"
                    :data-testid="`remove-member-${row.userId ?? ''}`"
                    :disabled="busyUsers[row.userId ?? ''] === true"
                    :aria-label="`${t('members.remove')} ${row.userId ?? ''}`"
                    @click="onRemove(row)"
                  >
                    {{ t("members.remove") }}
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </template>
      </template>
    </template>
  </div>
</template>

<style scoped>
.members-tab {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-4);
  max-width: 48rem;
}

.members-tab__add {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-2);
}

.members-tab__add-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--wp-space-2);
  align-items: center;
}

.members-tab__field {
  display: flex;
  flex: 1 1 12rem;
  min-width: 10rem;
}

.members-tab__field--role {
  flex: 0 1 14rem;
  min-width: 10rem;
}

.members-tab__error {
  margin: 0;
  color: var(--el-color-danger);
  font-size: var(--wp-font-size-sm);
}

.members-tab__list {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-2);
}

.members-tab__heading {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
}

.members-tab__hit {
  min-height: 32px;
  min-width: 32px;
}

.members-tab__suggestion {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--wp-space-2);
  width: 100%;
}

.members-tab__suggestion.is-disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.members-tab__suggestion-badge {
  flex-shrink: 0;
  font-size: var(--wp-font-size-sm);
  color: var(--wp-color-text-secondary, var(--el-text-color-secondary));
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip-path: inset(50%);
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
