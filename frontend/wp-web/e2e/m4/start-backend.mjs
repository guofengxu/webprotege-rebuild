import { spawn, spawnSync } from "node:child_process";
import { mkdtempSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, "../../../..");
const composeFile = join(repoRoot, "compose.yaml");
const jarDir = join(repoRoot, "backend/wp-app/target");
const adminPassword = process.env.WP_M4_ADMIN_PASSWORD;

if (adminPassword === undefined || adminPassword.length === 0) {
  console.error(
    "WP_M4_ADMIN_PASSWORD is required (set by playwright.m4.config.ts).",
  );
  process.exit(1);
}

const jarPath = findExecutableJar(jarDir);
if (jarPath === undefined) {
  console.error(
    `找不到可执行 jar：${jarDir}/wp-app-*.jar（排除 *.original）。请先执行：\n` +
      `  pnpm -C frontend --filter wp-web run build\n` +
      `  ./mvnw -q -pl :wp-app -am -Pwith-web -DskipTests package`,
  );
  process.exit(1);
}

run("docker", [
  "compose",
  "-f",
  composeFile,
  "up",
  "-d",
  "--wait",
  "postgres",
]);

run("docker", [
  "compose",
  "-f",
  composeFile,
  "exec",
  "-T",
  "postgres",
  "psql",
  "-U",
  "wp",
  "-d",
  "wp",
  "-c",
  "DROP DATABASE IF EXISTS wp_m4 WITH (FORCE)",
  "-c",
  "CREATE DATABASE wp_m4",
]);

const dataDir = mkdtempSync(join(tmpdir(), "wp-m4-data-"));
const javaBin =
  process.env.JAVA_HOME !== undefined && process.env.JAVA_HOME.length > 0
    ? join(process.env.JAVA_HOME, "bin", "java")
    : "java";

const child = spawn(
  javaBin,
  [
    "-jar",
    jarPath,
    "--server.port=18080",
    "--spring.datasource.url=jdbc:postgresql://localhost:5432/wp_m4",
    `--wp.data-dir=${dataDir}`,
    "--wp.bootstrap.admin.username=m4admin",
    `--wp.bootstrap.admin.password=${adminPassword}`,
    "--wp.bootstrap.admin.email=m4admin@example.invalid",
  ],
  {
    cwd: repoRoot,
    stdio: "inherit",
    env: process.env,
  },
);

const stop = () => {
  if (!child.killed) {
    child.kill("SIGTERM");
  }
};

process.on("SIGTERM", stop);
process.on("SIGINT", stop);

child.on("exit", (code, signal) => {
  if (signal !== null) {
    process.exit(1);
  }
  process.exit(code ?? 1);
});

function findExecutableJar(dir) {
  let names;
  try {
    names = readdirSync(dir);
  } catch {
    return undefined;
  }
  const match = names.find(
    (name) =>
      name.startsWith("wp-app-") &&
      name.endsWith(".jar") &&
      !name.endsWith(".jar.original") &&
      !name.includes(".original"),
  );
  return match === undefined ? undefined : join(dir, match);
}

function run(command, args) {
  const result = spawnSync(command, args, {
    cwd: repoRoot,
    stdio: "inherit",
    env: process.env,
  });
  if (result.error !== undefined) {
    console.error(result.error);
    process.exit(1);
  }
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}
