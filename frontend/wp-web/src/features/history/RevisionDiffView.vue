<script setup lang="ts">
import type { EntityNode, RevisionChange } from "@wp/api-types";
import { computed, toRef } from "vue";
import { RouterLink } from "vue-router";
import { useI18n } from "vue-i18n";
import { useWorkspaceRegistry } from "@/features/workspace/registry";
import { useWorkspaceRoute } from "@/features/workspace/useWorkspaceRoute";
import { problemMessage } from "@/shared/api/problem";
import { DEFAULT_DIFF_LIMIT, useRevisionDiff } from "./api";

const props = defineProps<{
  projectId: string;
  rev: number;
}>();

const { t } = useI18n();
const registry = useWorkspaceRegistry();
const { linkTo } = useWorkspaceRoute();

const projectId = toRef(props, "projectId");
const rev = toRef(props, "rev");
const query = useRevisionDiff(projectId, rev, DEFAULT_DIFF_LIMIT);

type ChangeGroup = {
  readonly subject: EntityNode;
  readonly changes: readonly RevisionChange[];
  readonly to: ReturnType<typeof linkTo>;
};

const groups = computed((): ChangeGroup[] => {
  const changes = query.data.value?.changes ?? [];
  const order: string[] = [];
  const byIri = new Map<string, ChangeGroup>();
  for (const change of changes) {
    const iri = change.subject.iri;
    const existing = byIri.get(iri);
    if (existing === undefined) {
      order.push(iri);
      byIri.set(iri, {
        subject: change.subject,
        changes: [change],
        to: linkTo(change.subject.entityType, change.subject.iri),
      });
    } else {
      byIri.set(iri, {
        ...existing,
        changes: [...existing.changes, change],
      });
    }
  }
  return order.map((iri) => {
    const group = byIri.get(iri);
    if (group === undefined) {
      throw new Error(`missing group for ${iri}`);
    }
    return group;
  });
});

const truncated = computed(() => {
  const data = query.data.value;
  return data !== undefined && data.total > data.limit;
});

const errorText = computed(() => {
  if (!query.isError.value) {
    return "";
  }
  return problemMessage(query.error.value, t);
});

function opLabel(op: string): string {
  if (op === "ADD") {
    return t("history.opAdd");
  }
  if (op === "REMOVE") {
    return t("history.opRemove");
  }
  return op;
}

</script>

<template>
  <section
    class="revision-diff"
    :aria-label="t('history.diffLabel')"
    :aria-busy="query.isFetching.value ? 'true' : 'false'"
  >
    <header class="revision-diff__header">
      <h2 class="revision-diff__title">
        {{ t("history.diffTitle", { rev }) }}
      </h2>
      <div
        v-if="registry.revisionActions.length > 0"
        class="revision-diff__actions"
      >
        <component
          :is="action"
          v-for="(action, index) in registry.revisionActions"
          :key="index"
          :project-id="projectId"
          :revision="rev"
        />
      </div>
    </header>

    <div
      v-if="query.isError.value"
      class="revision-diff__status"
      role="alert"
    >
      <p>{{ errorText || t("history.loadError") }}</p>
      <el-button
        type="primary"
        @click="query.refetch()"
      >
        {{ t("history.retry") }}
      </el-button>
    </div>

    <div
      v-else
      v-loading="query.isPending.value"
      class="revision-diff__body"
    >
      <el-alert
        v-if="truncated && query.data.value"
        type="warning"
        :closable="false"
        show-icon
        :title="
          t('history.truncated', {
            total: query.data.value.total,
            limit: query.data.value.limit,
          })
        "
      />

      <el-empty
        v-if="!query.isPending.value && groups.length === 0"
        :description="t('history.empty')"
      />

      <div
        v-for="group in groups"
        :key="group.subject.iri"
        class="revision-diff__group"
      >
        <h3 class="revision-diff__group-title">
          <RouterLink
            v-if="group.to !== null"
            :to="group.to"
            class="revision-diff__subject-link"
          >
            {{ group.subject.displayName }}
          </RouterLink>
          <span v-else>{{ group.subject.displayName }}</span>
          <span class="revision-diff__group-count">
            {{ t("history.groupChanges", { count: group.changes.length }) }}
          </span>
        </h3>
        <ul class="revision-diff__changes">
          <li
            v-for="(change, index) in group.changes"
            :key="`${change.op}:${change.text}:${index}`"
            class="revision-diff__change"
            :data-op="change.op"
          >
            <span
              class="revision-diff__op"
              :class="
                change.op === 'ADD'
                  ? 'revision-diff__op--add'
                  : change.op === 'REMOVE'
                    ? 'revision-diff__op--remove'
                    : 'revision-diff__op--unknown'
              "
            >
              <span
                class="revision-diff__op-icon"
                aria-hidden="true"
              >{{
                change.op === "ADD"
                  ? "+"
                  : change.op === "REMOVE"
                    ? "−"
                    : "·"
              }}</span>
              <span class="revision-diff__op-label">{{ opLabel(change.op) }}</span>
            </span>
            <code class="revision-diff__text">{{ change.text }}</code>
          </li>
        </ul>
      </div>
    </div>
  </section>
</template>

<style scoped>
.revision-diff {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  min-height: 0;
  height: 100%;
}

.revision-diff__header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem;
  justify-content: space-between;
}

.revision-diff__title {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 600;
}

.revision-diff__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.revision-diff__body {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  min-height: 8rem;
  overflow: auto;
}

.revision-diff__status {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.revision-diff__group-title {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.5rem;
  margin: 0 0 0.5rem;
  font-size: 1rem;
  font-weight: 600;
}

.revision-diff__subject-link {
  color: var(--wp-color-brand, var(--el-color-primary));
  text-decoration: underline;
}

.revision-diff__subject-link:focus-visible {
  outline: 2px solid var(--wp-color-brand, var(--el-color-primary));
  outline-offset: 2px;
}

.revision-diff__group-count {
  color: var(--el-text-color-secondary);
  font-size: 0.875rem;
  font-weight: 400;
}

.revision-diff__changes {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.revision-diff__change {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 0.75rem;
  align-items: start;
}

.revision-diff__op {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  font-size: 0.875rem;
  font-weight: 600;
  white-space: nowrap;
}

.revision-diff__op--add {
  color: var(--el-color-success);
}

.revision-diff__op--remove {
  color: var(--el-color-danger);
}

.revision-diff__op-icon {
  display: inline-flex;
  width: 1.25rem;
  height: 1.25rem;
  align-items: center;
  justify-content: center;
  border-radius: 0.25rem;
  border: 1px solid currentColor;
  font-family: var(--wp-font-mono, ui-monospace, monospace);
  line-height: 1;
}

.revision-diff__text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: var(
    --wp-font-mono,
    ui-monospace,
    SFMono-Regular,
    Menlo,
    Monaco,
    Consolas,
    monospace
  );
  font-size: 0.875rem;
}
</style>
