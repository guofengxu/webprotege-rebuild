import { computed, type ComputedRef } from "vue";
import {
  useRoute,
  useRouter,
  type LocationQuery,
  type RouteLocationRaw,
} from "vue-router";
import {
  entityTypeToKind,
  isWorkspaceKind,
  type EntityTypeName,
  type WorkspaceKind,
} from "./hierarchyKinds";

export type WorkspaceRouteApi = {
  readonly projectId: ComputedRef<string>;
  readonly kind: ComputedRef<WorkspaceKind>;
  readonly entityIri: ComputedRef<string | null>;
  readonly typeIri: ComputedRef<string | null>;
  selectEntity: (iri: string | null) => void;
  selectType: (iri: string | null) => void;
  setKind: (kind: WorkspaceKind) => void;
  linkTo: (entityType: EntityTypeName, iri: string) => RouteLocationRaw | null;
};

export function useWorkspaceRoute(): WorkspaceRouteApi {
  const route = useRoute();
  const router = useRouter();

  const projectId = computed(() => {
    const raw = route.params.projectId;
    return typeof raw === "string" ? raw : "";
  });

  const kind = computed<WorkspaceKind>(() => {
    const raw = route.params.kind;
    return typeof raw === "string" && isWorkspaceKind(raw) ? raw : "class";
  });

  const entityIri = computed(() => queryString(route.query.entity));
  const typeIri = computed(() => queryString(route.query.type));

  function selectEntity(iri: string | null): void {
    void router.replace({
      query: withQueryValue(route.query, "entity", iri),
    });
  }

  function selectType(iri: string | null): void {
    void router.replace({
      query: withQueryValue(route.query, "type", iri),
    });
  }

  function setKind(next: WorkspaceKind): void {
    const id = projectId.value;
    if (id === "") {
      return;
    }
    void router.replace({
      path: `/projects/${id}/${next}`,
      query: {},
    });
  }

  function linkTo(
    entityType: EntityTypeName,
    iri: string,
  ): RouteLocationRaw | null {
    const mapped = entityTypeToKind(entityType);
    if (mapped === null) {
      return null;
    }
    const id = projectId.value;
    if (id === "") {
      return null;
    }
    return {
      path: `/projects/${id}/${mapped}`,
      query: { entity: iri },
    };
  }

  return {
    projectId,
    kind,
    entityIri,
    typeIri,
    selectEntity,
    selectType,
    setKind,
    linkTo,
  };
}

function queryString(value: unknown): string | null {
  if (typeof value === "string" && value.length > 0) {
    return value;
  }
  if (Array.isArray(value)) {
    const first = value.find(
      (entry): entry is string => typeof entry === "string" && entry.length > 0,
    );
    return first ?? null;
  }
  return null;
}

function withQueryValue(
  query: LocationQuery,
  key: string,
  value: string | null,
): LocationQuery {
  const next: LocationQuery = { ...query };
  if (value === null || value === "") {
    delete next[key];
  } else {
    next[key] = value;
  }
  return next;
}
