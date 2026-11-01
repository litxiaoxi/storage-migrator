<script setup lang="ts">
/**
 * 插件菜单图标：与插件 Logo 同款的双存储容器 + 迁移箭头。
 * 用 currentColor 描边，跟随控制台主题色；尺寸默认 1em，由菜单字号决定。
 */
withDefaults(
  defineProps<{
    size?: number | string
  }>(),
  {
    size: '1em',
  },
)
</script>

<template>
  <svg
    xmlns="http://www.w3.org/2000/svg"
    viewBox="0 0 24 24"
    :width="size"
    :height="size"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <!-- 源存储策略：淡色，表示将被迁出 -->
    <rect x="1.75" y="6.25" width="7" height="11.5" rx="1.75" opacity="0.45" />
    <!-- 迁移箭头 -->
    <path d="M10 12h3.8" />
    <path d="M11.9 10.1 13.8 12l-1.9 1.9" />
    <!-- 目标存储策略：实色，表示迁入 -->
    <rect x="15.25" y="6.25" width="7" height="11.5" rx="1.75" />
  </svg>
</template>
