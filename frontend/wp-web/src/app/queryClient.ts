import { QueryClient } from "@tanstack/vue-query";
import { ApiError } from "@/shared/api/problem";

function retryQuery(failureCount: number, error: Error): boolean {
  if (error instanceof ApiError && error.status < 500) {
    return false;
  }
  return failureCount < 1;
}

export function createAppQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        retry: retryQuery,
      },
    },
  });
}
