<script lang="ts">
  import { Button, Input, Textarea, Badge } from '@etzhayyim/design-system';
  import { callXrpc, type XrpcResult } from './lib/xrpc';

  interface Props {
    xrpcBase?: string;
    embed?: boolean;
    onResult?: (r: XrpcResult) => void;
  }
  let { xrpcBase = '', embed = false, onResult }: Props = $props();

  const nsid = {
    listEvents: 'com.etzhayyim.apps.calendar.listEvents',
    createEvent: 'com.etzhayyim.apps.calendar.createEvent',
    updateEvent: 'com.etzhayyim.apps.calendar.updateEvent',
    deleteEvent: 'com.etzhayyim.apps.calendar.deleteEvent'
  } as const;

  type View = 'day' | 'week' | 'month' | 'schedule';
  let view = $state<View>('month');
  let cursor = $state(new Date()); // first-of-month anchor
  let composing = $state(false);
  let composerDate = $state<Date | null>(null);

  // Stub events keyed by YYYY-MM-DD
  type Event = { id: string; title: string; start: string; end: string; calendar: string; color: string; allDay?: boolean };
  let events = $state<Event[]>([
    { id: 'e1', title: 'Monthly Close Review', start: '17:00', end: '18:00', calendar: 'finance', color: 'bg-blue-500' },
    { id: 'e2', title: 'AR aging stand-up', start: '10:00', end: '10:30', calendar: 'finance', color: 'bg-blue-500' },
    { id: 'e3', title: 'HRBP interview · Yamada', start: '14:00', end: '15:00', calendar: 'hr', color: 'bg-emerald-500' },
    { id: 'e4', title: 'Vendor Q2 sync', start: '11:00', end: '11:45', calendar: 'procurement', color: 'bg-orange-500' },
    { id: 'e5', title: 'Board pack lock', allDay: true, start: '', end: '', calendar: 'finance', color: 'bg-blue-500' }
  ]);
  // distribute fake events across the visible month
  const eventsByDay = $derived.by(() => {
    const map = new Map<string, Event[]>();
    const today = new Date();
    const offsets = [0, 1, 3, 7, 14, 21];
    events.forEach((ev, i) => {
      const d = new Date(today);
      d.setDate(today.getDate() + offsets[i % offsets.length]);
      const key = d.toISOString().slice(0, 10);
      const arr = map.get(key) ?? [];
      arr.push(ev);
      map.set(key, arr);
    });
    return map;
  });

  const calendars = [
    { id: 'me', label: 'My calendar', color: 'bg-indigo-500', on: true },
    { id: 'finance', label: 'finance', color: 'bg-blue-500', on: true },
    { id: 'hr', label: 'hr', color: 'bg-emerald-500', on: true },
    { id: 'procurement', label: 'procurement', color: 'bg-orange-500', on: true },
    { id: 'security', label: 'security', color: 'bg-rose-500', on: false }
  ];

  // Compose form
  let newTitle = $state('');
  let newDate = $state(new Date().toISOString().slice(0, 10));
  let newStart = $state('09:00');
  let newEnd = $state('10:00');
  let newRRule = $state('');
  let newCalendar = $state('finance');
  let newAttendees = $state('');

  let loading = $state(false);

  async function call(kind: string, payload: Record<string, unknown>) {
    loading = true;
    try {
      const r = await callXrpc(xrpcBase, kind, payload);
      onResult?.(r);
      return r;
    } finally {
      loading = false;
    }
  }

  // Calendar grid math
  const monthLabel = $derived(
    cursor.toLocaleDateString('en-US', { month: 'long', year: 'numeric' })
  );
  const today = new Date();
  const todayKey = today.toISOString().slice(0, 10);

  const weekdays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

  const monthGrid = $derived.by(() => {
    const first = new Date(cursor.getFullYear(), cursor.getMonth(), 1);
    const startDow = first.getDay();
    const daysInMonth = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 0).getDate();
    const cells: Array<{ d: Date; inMonth: boolean; key: string }> = [];
    // leading blanks (prev month)
    for (let i = startDow - 1; i >= 0; i--) {
      const d = new Date(cursor.getFullYear(), cursor.getMonth(), -i);
      cells.push({ d, inMonth: false, key: d.toISOString().slice(0, 10) });
    }
    // current month
    for (let i = 1; i <= daysInMonth; i++) {
      const d = new Date(cursor.getFullYear(), cursor.getMonth(), i);
      cells.push({ d, inMonth: true, key: d.toISOString().slice(0, 10) });
    }
    // trailing
    while (cells.length % 7 !== 0) {
      const last = cells[cells.length - 1].d;
      const d = new Date(last.getFullYear(), last.getMonth(), last.getDate() + 1);
      cells.push({ d, inMonth: false, key: d.toISOString().slice(0, 10) });
    }
    return cells;
  });

  // Mini calendar (always current cursor)
  const miniGrid = $derived(monthGrid);

  function shift(delta: number) {
    if (view === 'month') cursor = new Date(cursor.getFullYear(), cursor.getMonth() + delta, 1);
    else if (view === 'week') cursor = new Date(cursor.getFullYear(), cursor.getMonth(), cursor.getDate() + 7 * delta);
    else if (view === 'day') cursor = new Date(cursor.getFullYear(), cursor.getMonth(), cursor.getDate() + delta);
    else cursor = new Date(cursor.getFullYear(), cursor.getMonth() + delta, 1);
  }

  function openComposer(d?: Date) {
    composerDate = d ?? new Date();
    newDate = composerDate.toISOString().slice(0, 10);
    newTitle = '';
    composing = true;
  }

  async function submitEvent() {
    const start = `${newDate}T${newStart}:00`;
    const end = `${newDate}T${newEnd}:00`;
    const attendeeDids = newAttendees.split('\n').map((l) => l.trim()).filter(Boolean);
    await call(nsid.createEvent, {
      title: newTitle,
      start,
      end,
      rrule: newRRule || undefined,
      attendeeDids,
      calendarId: newCalendar
    });
    composing = false;
    newTitle = '';
  }

  // Week / day grid (hour rows)
  const hours = Array.from({ length: 24 }, (_, i) => i);
  const weekDays = $derived.by(() => {
    const start = new Date(cursor);
    start.setDate(cursor.getDate() - cursor.getDay());
    return Array.from({ length: 7 }, (_, i) => {
      const d = new Date(start);
      d.setDate(start.getDate() + i);
      return d;
    });
  });
</script>

<div class="grid grid-cols-[240px_minmax(0,1fr)] gap-0 h-full min-h-[calc(100vh-160px)] -m-5">
  <!-- Sidebar: mini-cal + my calendars -->
  <aside class="border-r border-etzhayyim-border bg-etzhayyim-sidebar p-3 flex flex-col gap-4 overflow-y-auto">
    <Button size="md" variant="solid-fill" onclick={() => openComposer()}>＋ Create</Button>

    <!-- Mini calendar -->
    <div class="text-xs">
      <div class="flex items-center justify-between mb-2">
        <span class="font-semibold">{monthLabel}</span>
        <div class="flex gap-1">
          <button type="button" class="w-6 h-6 rounded hover:bg-etzhayyim-hover" onclick={() => shift(-1)}>‹</button>
          <button type="button" class="w-6 h-6 rounded hover:bg-etzhayyim-hover" onclick={() => shift(1)}>›</button>
        </div>
      </div>
      <div class="grid grid-cols-7 text-center text-[10px] text-etzhayyim-muted">
        {#each ['S', 'M', 'T', 'W', 'T', 'F', 'S'] as d}<span>{d}</span>{/each}
      </div>
      <div class="grid grid-cols-7 text-center text-[11px] mt-1">
        {#each miniGrid as c}
          <button
            type="button"
            class="aspect-square grid place-items-center rounded-full hover:bg-etzhayyim-hover"
            class:text-etzhayyim-muted={!c.inMonth}
            class:bg-etzhayyim-accent={c.key === todayKey}
            class:text-white={c.key === todayKey}
            class:font-semibold={c.key === todayKey}
            onclick={() => (cursor = new Date(c.d))}
          >{c.d.getDate()}</button>
        {/each}
      </div>
    </div>

    <!-- My calendars -->
    <div>
      <p class="text-[11px] uppercase tracking-wider text-etzhayyim-muted mb-2">My calendars</p>
      <ul class="text-sm space-y-1">
        {#each calendars as c}
          <li class="flex items-center gap-2">
            <span class={`inline-block w-3 h-3 rounded-sm ${c.color}`} class:opacity-30={!c.on}></span>
            <span class:line-through={!c.on} class:text-etzhayyim-muted={!c.on}>{c.label}</span>
          </li>
        {/each}
      </ul>
    </div>

    <div class="mt-auto text-[11px] text-etzhayyim-muted">
      <p>RFC 5545 RRULE · attendee DIDs · 3 visibility tiers</p>
      {#if xrpcBase}<p class="mt-1">via {xrpcBase}</p>{/if}
    </div>
  </aside>

  <!-- Main: top bar + grid -->
  <section class="flex flex-col min-w-0">
    <header class="px-5 py-3 border-b border-etzhayyim-border flex items-center gap-3 flex-wrap">
      <Button size="xs" variant="outline" onclick={() => (cursor = new Date())}>Today</Button>
      <button type="button" class="w-8 h-8 rounded-full hover:bg-etzhayyim-hover grid place-items-center" onclick={() => shift(-1)}>‹</button>
      <button type="button" class="w-8 h-8 rounded-full hover:bg-etzhayyim-hover grid place-items-center" onclick={() => shift(1)}>›</button>
      <h2 class="text-lg font-semibold">{monthLabel}</h2>
      <div class="ml-auto flex items-center gap-2">
        <Badge value={loading ? 'syncing…' : 'live'} variant={loading ? 'warning' : 'success'} />
        <div class="flex rounded-full border border-etzhayyim-border p-0.5 text-xs">
          {#each (['day', 'week', 'month', 'schedule'] as View[]) as v}
            <button
              type="button"
              class="px-3 py-1 rounded-full"
              class:bg-etzhayyim-accent={view === v}
              class:text-white={view === v}
              class:font-semibold={view === v}
              onclick={() => (view = v)}
            >{v}</button>
          {/each}
        </div>
      </div>
    </header>

    {#if view === 'month'}
      <div class="grid grid-cols-7 border-b border-etzhayyim-border text-[11px] uppercase tracking-wider text-etzhayyim-muted">
        {#each weekdays as d}
          <div class="px-3 py-2 border-r border-etzhayyim-border last:border-r-0">{d}</div>
        {/each}
      </div>
      <div class="flex-1 grid grid-cols-7 grid-rows-6 overflow-auto">
        {#each monthGrid as c}
          {@const dayEvents = eventsByDay.get(c.key) ?? []}
          <button
            type="button"
            class="text-left border-r border-b border-etzhayyim-border last:border-r-0 p-1.5 min-h-[100px] flex flex-col gap-1 hover:bg-etzhayyim-hover"
            class:bg-etzhayyim-input={!c.inMonth}
            onclick={() => openComposer(c.d)}
          >
            <div class="flex items-center justify-between text-xs">
              <span
                class="inline-grid place-items-center w-6 h-6 rounded-full text-[11px]"
                class:bg-etzhayyim-accent={c.key === todayKey}
                class:text-white={c.key === todayKey}
                class:font-semibold={c.key === todayKey}
                class:text-etzhayyim-muted={!c.inMonth}
              >{c.d.getDate()}</span>
            </div>
            {#each dayEvents.slice(0, 3) as ev}
              <div class={`truncate text-[11px] rounded px-1.5 py-0.5 text-white ${ev.color}`}>
                {#if ev.allDay}{ev.title}{:else}{ev.start} {ev.title}{/if}
              </div>
            {/each}
            {#if dayEvents.length > 3}
              <span class="text-[10px] text-etzhayyim-muted">+{dayEvents.length - 3} more</span>
            {/if}
          </button>
        {/each}
      </div>

    {:else if view === 'week'}
      <div class="grid grid-cols-[60px_repeat(7,minmax(0,1fr))] border-b border-etzhayyim-border text-[11px] uppercase tracking-wider text-etzhayyim-muted">
        <div></div>
        {#each weekDays as d}
          <div class="px-2 py-2 border-l border-etzhayyim-border text-center">
            <div>{weekdays[d.getDay()]}</div>
            <div class="text-2xl font-semibold text-etzhayyim-text"
              class:text-etzhayyim-accent={d.toDateString() === today.toDateString()}>{d.getDate()}</div>
          </div>
        {/each}
      </div>
      <div class="flex-1 overflow-auto">
        <div class="grid grid-cols-[60px_repeat(7,minmax(0,1fr))]">
          {#each hours as h}
            <div class="border-b border-etzhayyim-border/40 text-[10px] text-etzhayyim-muted px-1 py-0.5 h-12">{h.toString().padStart(2,'0')}:00</div>
            {#each weekDays as d}
              {@const evs = (eventsByDay.get(d.toISOString().slice(0,10)) ?? []).filter(e => !e.allDay && parseInt(e.start.split(':')[0],10) === h)}
              <button type="button"
                class="border-l border-b border-etzhayyim-border/40 h-12 hover:bg-etzhayyim-hover relative text-left"
                onclick={() => openComposer(d)}
              >
                {#each evs as ev}
                  <div class={`absolute inset-x-0.5 top-0.5 rounded px-1 text-[10px] text-white truncate ${ev.color}`}>
                    {ev.start} {ev.title}
                  </div>
                {/each}
              </button>
            {/each}
          {/each}
        </div>
      </div>

    {:else if view === 'day'}
      <div class="px-5 py-3 border-b border-etzhayyim-border">
        <p class="text-2xl font-semibold">{cursor.toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' })}</p>
      </div>
      <div class="flex-1 overflow-auto">
        {#each hours as h}
          {@const evs = (eventsByDay.get(cursor.toISOString().slice(0,10)) ?? []).filter(e => !e.allDay && parseInt(e.start.split(':')[0],10) === h)}
          <div class="grid grid-cols-[80px_minmax(0,1fr)] border-b border-etzhayyim-border/40 min-h-[48px]">
            <div class="text-xs text-etzhayyim-muted px-2 py-1">{h.toString().padStart(2, '0')}:00</div>
            <button type="button" class="hover:bg-etzhayyim-hover text-left p-1" onclick={() => openComposer(cursor)}>
              {#each evs as ev}
                <div class={`rounded px-2 py-1 text-xs text-white ${ev.color}`}>{ev.start}–{ev.end} · {ev.title}</div>
              {/each}
            </button>
          </div>
        {/each}
      </div>

    {:else}
      <!-- schedule view -->
      <div class="flex-1 overflow-auto p-5">
        <ul class="divide-y divide-etzhayyim-border">
          {#each [...eventsByDay.entries()].sort(([a],[b]) => a.localeCompare(b)) as [day, evs]}
            <li class="py-3 flex gap-4">
              <div class="w-24 flex-shrink-0">
                <p class="text-xs text-etzhayyim-muted">{new Date(day).toLocaleDateString('en-US', { weekday: 'short' })}</p>
                <p class="text-2xl font-semibold">{new Date(day).getDate()}</p>
              </div>
              <ul class="flex-1 space-y-1">
                {#each evs as ev}
                  <li class="flex items-center gap-2">
                    <span class={`inline-block w-2 h-2 rounded-full ${ev.color}`}></span>
                    <span class="text-sm">{ev.allDay ? 'All day' : `${ev.start}–${ev.end}`}</span>
                    <span class="text-sm text-etzhayyim-secondary">{ev.title}</span>
                    <Badge value={ev.calendar} variant="default" />
                  </li>
                {/each}
              </ul>
            </li>
          {/each}
        </ul>
      </div>
    {/if}
  </section>
</div>

{#if composing}
  <div
    role="presentation"
    tabindex="-1"
    class="fixed inset-0 z-40 bg-black/40 grid place-items-center"
    onclick={() => (composing = false)}
    onkeydown={(e) => e.key === 'Escape' && (composing = false)}
  >
    <!-- svelte-ignore a11y_click_events_have_key_events -->
    <div
      role="dialog"
      aria-modal="true"
      tabindex="-1"
      class="w-[480px] max-w-[calc(100vw-2rem)] rounded-xl bg-etzhayyim-card border border-etzhayyim-border shadow-2xl flex flex-col"
      onclick={(e) => e.stopPropagation()}
    >
      <header class="px-4 py-3 border-b border-etzhayyim-border flex items-center justify-between">
        <span class="text-sm font-semibold">Create event</span>
        <button type="button" class="text-etzhayyim-muted" onclick={() => (composing = false)}>✕</button>
      </header>
      <div class="px-4 py-3 flex flex-col gap-3">
        <Input bind:value={newTitle} blockSize="md" placeholder="Add title" />
        <div class="grid grid-cols-3 gap-2">
          <Input bind:value={newDate} blockSize="md" type="date" />
          <Input bind:value={newStart} blockSize="md" type="time" />
          <Input bind:value={newEnd} blockSize="md" type="time" />
        </div>
        <Input bind:value={newRRule} blockSize="md" placeholder="RRULE (optional, e.g. FREQ=WEEKLY)" />
        <div class="flex gap-2">
          <select bind:value={newCalendar} class="rounded border border-etzhayyim-border bg-etzhayyim-input px-2 py-1 text-sm flex-1">
            {#each calendars.filter(c => c.on) as c}
              <option value={c.id}>{c.label}</option>
            {/each}
          </select>
        </div>
        <span class="text-xs text-etzhayyim-secondary">Attendee DIDs (one per line)</span>
        <Textarea bind:value={newAttendees} rows={3} />
      </div>
      <footer class="px-4 py-3 border-t border-etzhayyim-border flex items-center gap-2">
        <Button size="sm" variant="solid-fill" onclick={submitEvent}>Save</Button>
        <Button size="sm" variant="text" onclick={() => (composing = false)}>Cancel</Button>
        <span class="ml-auto text-[11px] text-etzhayyim-muted">Signal E2E for confidential</span>
      </footer>
    </div>
  </div>
{/if}
