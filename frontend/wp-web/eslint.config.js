import pluginVue from "eslint-plugin-vue";
import { withVueTs, vueTsConfigs } from "@vue/eslint-config-typescript";

export default withVueTs(
  {
    ignores: ["dist/**", "playwright-report/**", "test-results/**"],
  },
  pluginVue.configs["flat/recommended"],
  vueTsConfigs.recommended,
  {
    rules: {
      "@typescript-eslint/no-explicit-any": "error",
    },
  },
  {
    files: ["src/App.vue"],
    rules: {
      "vue/multi-word-component-names": "off",
    },
  },
);
