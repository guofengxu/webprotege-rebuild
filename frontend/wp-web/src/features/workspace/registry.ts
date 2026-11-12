import { inject, type Component, type InjectionKey } from "vue";
import type { EntityTypeName, WorkspaceKind } from "./hierarchyKinds";

export type DetailTabRegistration = {
  readonly key: string;
  readonly labelKey: string;
  readonly component: Component;
};

export type FeatureRegistration = {
  readonly hierarchyPanels?: Partial<Record<WorkspaceKind, Component>>;
  readonly detailEditors?: Partial<Record<EntityTypeName, Component>>;
  readonly detailTabs?: readonly DetailTabRegistration[];
  readonly workspaceToolbar?: readonly Component[];
  readonly hierarchyToolbar?: readonly Component[];
  readonly revisionActions?: readonly Component[];
};

export type WorkspaceRegistry = {
  readonly hierarchyPanels: Partial<Record<WorkspaceKind, Component>>;
  readonly detailEditors: Partial<Record<EntityTypeName, Component>>;
  readonly detailTabs: readonly DetailTabRegistration[];
  readonly workspaceToolbar: readonly Component[];
  readonly hierarchyToolbar: readonly Component[];
  readonly revisionActions: readonly Component[];
};

export type RegistrationModule = {
  readonly registration?: FeatureRegistration;
};

const WorkspaceRegistryKey: InjectionKey<WorkspaceRegistry> = Symbol(
  "wp.workspace.registry",
);

const registrationModules = import.meta.glob<RegistrationModule>(
  "/src/features/**/register.ts",
  { eager: true, exhaustive: true },
);

const defaultRegistry = mergeRegistrations(registrationModules);

export function mergeRegistrations(
  modules: Readonly<Record<string, RegistrationModule>>,
): WorkspaceRegistry {
  const hierarchyPanels: Partial<Record<WorkspaceKind, Component>> = {};
  const detailEditors: Partial<Record<EntityTypeName, Component>> = {};
  const detailTabs: DetailTabRegistration[] = [];
  const workspaceToolbar: Component[] = [];
  const hierarchyToolbar: Component[] = [];
  const revisionActions: Component[] = [];

  const sortedPaths = Object.keys(modules).sort();
  for (const path of sortedPaths) {
    const registration = modules[path]?.registration;
    if (registration === undefined) {
      continue;
    }

    for (const [kind, component] of Object.entries(
      registration.hierarchyPanels ?? {},
    )) {
      const key = kind as WorkspaceKind;
      if (hierarchyPanels[key] !== undefined) {
        throw new Error(
          `Duplicate hierarchy panel registration for kind "${key}" from ${path}`,
        );
      }
      if (component !== undefined) {
        hierarchyPanels[key] = component;
      }
    }

    for (const [entityType, component] of Object.entries(
      registration.detailEditors ?? {},
    )) {
      const key = entityType as EntityTypeName;
      if (detailEditors[key] !== undefined) {
        throw new Error(
          `Duplicate detail editor registration for entity type "${key}" from ${path}`,
        );
      }
      if (component !== undefined) {
        detailEditors[key] = component;
      }
    }

    detailTabs.push(...(registration.detailTabs ?? []));
    workspaceToolbar.push(...(registration.workspaceToolbar ?? []));
    hierarchyToolbar.push(...(registration.hierarchyToolbar ?? []));
    revisionActions.push(...(registration.revisionActions ?? []));
  }

  return {
    hierarchyPanels,
    detailEditors,
    detailTabs,
    workspaceToolbar,
    hierarchyToolbar,
    revisionActions,
  };
}

export function useWorkspaceRegistry(): WorkspaceRegistry {
  return inject(WorkspaceRegistryKey, defaultRegistry);
}

export { WorkspaceRegistryKey };
