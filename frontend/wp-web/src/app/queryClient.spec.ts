import { expect, test } from "vitest";
import { ApiError } from "@/shared/api/problem";
import { createAppQueryClient } from "./queryClient";

test("keeps server data fresh for 30s and does not refetch on focus", () => {
  const client = createAppQueryClient();
  const queries = client.getDefaultOptions().queries;
  expect(queries?.staleTime).toBe(30_000);
  expect(queries?.refetchOnWindowFocus).toBe(false);
  expect(client.getDefaultOptions().mutations?.onError).toBeUndefined();
});

test("does not retry an ApiError below 500 and retries other failures once", async () => {
  const client = createAppQueryClient();

  let clientErrors = 0;
  await expect(
    client.fetchQuery({
      queryKey: ["client-error"],
      retryDelay: 0,
      queryFn: () => {
        clientErrors += 1;
        throw new ApiError(409, { code: "FRAME_CHANGED", detail: "changed" });
      },
    }),
  ).rejects.toBeInstanceOf(ApiError);
  expect(clientErrors).toBe(1);

  let serverErrors = 0;
  await expect(
    client.fetchQuery({
      queryKey: ["server-error"],
      retryDelay: 0,
      queryFn: () => {
        serverErrors += 1;
        throw new ApiError(500);
      },
    }),
  ).rejects.toBeInstanceOf(ApiError);
  expect(serverErrors).toBe(2);

  let networkErrors = 0;
  await expect(
    client.fetchQuery({
      queryKey: ["network-error"],
      retryDelay: 0,
      queryFn: () => {
        networkErrors += 1;
        throw new TypeError("Failed to fetch");
      },
    }),
  ).rejects.toBeInstanceOf(TypeError);
  expect(networkErrors).toBe(2);
});
