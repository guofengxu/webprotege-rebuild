<script setup lang="ts">
import en from "element-plus/es/locale/lang/en";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import { computed } from "vue";
import { useAppLocale } from "@/app/i18n";

const { locale } = useAppLocale();
const elementLocale = computed(() => (locale.value === "zh-CN" ? zhCn : en));
</script>

<template>
  <el-config-provider :locale="elementLocale">
    <RouterView />
  </el-config-provider>
</template>
