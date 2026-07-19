<script lang="ts">
  import CalendarApp from './CalendarApp.svelte';
</script>

<main class="min-h-screen p-6 bg-etzhayyim-bg text-etzhayyim-text">
  <div class="max-w-5xl mx-auto">
    <CalendarApp />
  </div>
</main>

<style>
  :global(html), :global(body), :global(#app) {
    margin: 0;
    height: 100%;
  }
  :global(body) {
    font-family:
      'Noto Sans JP', -apple-system, BlinkMacSystemFont, 'Segoe UI',
      'Hiragino Kaku Gothic ProN', sans-serif;
  }
</style>
