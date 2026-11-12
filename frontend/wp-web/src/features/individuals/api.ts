import { useQuery } from "@tanstack/vue-query";
import type { IndividualPage } from "@wp/api-types";
import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useApiClient, type ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";

export async function fetchIndividuals(
  client: ApiClient,
  projectId: string,
  typeIri: string,
  page: number,
  size: number,
): Promise<IndividualPage> {
  return unwrap(
    await client.GET("/api/projects/{id}/individuals", {
      params: {
        path: { id: projectId },
        query: { type: typeIri, page, size },
      },
    }),
  );
}

/**
 * Keep the previous page only when paging (or resizing) within the same
 * project + type. Switching class must not show the previous class's rows.
 */
export function keepPreviousIndividualsPage(
  previousData: IndividualPage | undefined,
  previousQueryKey: readonly unknown[] | undefined,
  projectId: string,
  typeIri: string,
): IndividualPage | undefined {
  if (previousData === undefined || previousQueryKey === undefined) {
    return undefined;
  }
  if (
    previousQueryKey[0] === "project" &&
    previousQueryKey[1] === projectId &&
    previousQueryKey[2] === "individuals" &&
    previousQueryKey[3] === typeIri
  ) {
    return previousData;
  }
  return undefined;
}

export function useIndividuals(
  projectId: MaybeRefOrGetter<string>,
  typeIri: MaybeRefOrGetter<string | null | undefined>,
  page: MaybeRefOrGetter<number>,
  size: MaybeRefOrGetter<number>,
) {
  const client = useApiClient();
  return useQuery({
    queryKey: computed(() =>
      queryKeys.individuals(
        toValue(projectId),
        toValue(typeIri) ?? "",
        toValue(page),
        toValue(size),
      ),
    ),
    queryFn: () => {
      const type = toValue(typeIri);
      if (type === null || type === undefined || type === "") {
        throw new Error("typeIri is required");
      }
      return fetchIndividuals(
        client,
        toValue(projectId),
        type,
        toValue(page),
        toValue(size),
      );
    },
    enabled: computed(() => {
      const type = toValue(typeIri);
      return (
        toValue(projectId).length > 0 &&
        type !== null &&
        type !== undefined &&
        type.length > 0
      );
    }),
    placeholderData: (previousData, previousQuery) =>
      keepPreviousIndividualsPage(
        previousData,
        previousQuery?.queryKey,
        toValue(projectId),
        toValue(typeIri) ?? "",
      ),
  });
}
