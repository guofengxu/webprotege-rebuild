import type zhCN from "./zh-CN";

const enUS = {
  events: {
    connecting: "Connecting to live updates…",
    reconnecting: "Live connection lost, reconnecting…",
    stopped: "Live updates stopped",
    retry: "Retry",
    stopReasons: {
      forbidden: "You do not have permission to receive live updates for this project",
      notFound: "Project not found; live updates unavailable",
      conflict: "Project unavailable; live updates stopped",
      unauthorized: "Session expired; please sign in again",
    },
  },
} satisfies typeof zhCN;

export default enUS;
