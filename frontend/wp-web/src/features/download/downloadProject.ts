import type { ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";

export type DownloadFormatCode = "owl" | "ttl" | "owx" | "omn" | "ofn";

export type DownloadProjectOptions = {
  readonly format: DownloadFormatCode;
  readonly revision?: number;
};

export type DownloadProjectResult = {
  readonly blob: Blob;
  readonly fileName: string;
};

export async function downloadProject(
  client: ApiClient,
  projectId: string,
  options: DownloadProjectOptions,
): Promise<DownloadProjectResult> {
  const query: { format: string; revision?: number } = {
    format: options.format,
  };
  if (options.revision !== undefined) {
    query.revision = options.revision;
  }

  const result = await client.GET("/api/projects/{id}/download", {
    params: {
      path: { id: projectId },
      query,
    },
    parseAs: "blob",
  });
  const blob = unwrap(result);
  const fileName =
    parseContentDispositionFileName(
      result.response.headers.get("Content-Disposition"),
    ) ?? `${projectId}.${options.format}`;
  return { blob, fileName };
}

export function parseContentDispositionFileName(
  header: string | null,
): string | undefined {
  if (header === null || header.length === 0) {
    return undefined;
  }

  const star = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;\s]+)/i.exec(header);
  if (star?.[1] !== undefined) {
    try {
      const decoded = decodeURIComponent(stripQuotes(star[1]));
      if (decoded.length > 0) {
        return decoded;
      }
    } catch {
      // Fall through to filename= or undefined.
    }
  }

  const plain = /filename\s*=\s*(?:"((?:\\.|[^"])*)"|([^;\s]+))/i.exec(header);
  if (plain === null) {
    return undefined;
  }
  const value = stripQuotes((plain[1] ?? plain[2] ?? "").trim());
  return value.length > 0 ? value : undefined;
}

function stripQuotes(value: string): string {
  if (value.startsWith('"') && value.endsWith('"') && value.length >= 2) {
    return value.slice(1, -1);
  }
  return value;
}
