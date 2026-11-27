import type { DiscoveryChat, DiscoveryController, DiscoveryScanResult, DiscoveryVideo } from "./telegram-discovery";

export interface TelegramSectionHost {
  controller: DiscoveryController;
  hasLibraryChannel: () => boolean;
  isFirstTime: () => boolean;
  onFirstConsentAgreed: () => Promise<void>;
  onPlayVideo: (video: DiscoveryVideo) => void;
  onAddedToLibrary: (video: DiscoveryVideo) => Promise<void>;
  onBackToMenu: () => void;
  inLibraryIds: () => Set<string>;
}

function formatDuration(sec?: number): string {
  if (!sec || sec <= 0) return "";
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m ${s}s`;
}

function formatSize(bytes?: number): string {
  if (!bytes || bytes <= 0) return "";
  const mb = bytes / (1024 * 1024);
  return mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${Math.round(mb)} MB`;
}

function formatCountLabel(r: DiscoveryScanResult): string {
  if (r.videoCount > 0 && r.fileCount > 0) return `about ${r.videoCount + r.fileCount} videos`;
  if (r.videoCount > 0) return `${r.videoCount} ${r.videoCount === 1 ? "video" : "videos"}`;
  if (r.fileCount > 0) return `about ${r.fileCount} ${r.fileCount === 1 ? "file" : "files"} that may be videos`;
  return "No videos";
}

export function renderTelegramSection(container: HTMLElement, host: TelegramSectionHost): void {
  let mode: "home" | "prompt" | "scanning" | "results" | "chat" | "advanced" =
    host.controller.getDoc().chosenChats.length > 0 ? "home" : "prompt";
  let showZero = false;
  let activeChat: DiscoveryChat | null = null;
  let videos: DiscoveryVideo[] = [];
  let scanProgress = { done: 0, total: 0, results: host.controller.getDoc().scanResults };
  let statusMsg = "";

  const draw = () => {
    container.innerHTML = "";
    const doc = host.controller.getDoc();

    if (mode === "prompt") {
      const box = document.createElement("div");
      box.className = "tg-prompt";
      box.innerHTML = `
        <h2>On this TV</h2>
        <p>Cablegram will look in your channels and groups on this TV for long videos. Nothing from other chats leaves this TV.</p>
        ${host.isFirstTime() ? "<p>From now on, opening this account on this TV needs your OK in Telegram.</p>" : ""}
        <div class="tg-actions">
          <button id="tgLookForVideos" class="focusable" data-autofocus>Look for videos</button>
          <button id="tgNotNow" class="focusable">Not now</button>
        </div>
      `;
      container.appendChild(box);
      box.querySelector("#tgLookForVideos")?.addEventListener("click", async () => {
        await host.onFirstConsentAgreed();
        mode = "scanning";
        draw();
        await host.controller.scan((done, total, results) => {
          scanProgress = { done, total, results };
          draw();
        });
        mode = "results";
        draw();
      });
      box.querySelector("#tgNotNow")?.addEventListener("click", () => {
        if (doc.chosenChats.length > 0) {
          mode = "home";
          draw();
        } else {
          host.onBackToMenu();
        }
      });
      return;
    }

    if (mode === "scanning") {
      const box = document.createElement("div");
      box.className = "tg-scanning";
      box.innerHTML = `
        <h2>Looking for videos on this TV...</h2>
        <p>${scanProgress.done} / ${scanProgress.total || "?"} chats</p>
        <button id="tgStopScan" class="focusable" data-autofocus>Stop</button>
      `;
      container.appendChild(box);
      box.querySelector("#tgStopScan")?.addEventListener("click", () => {
        host.controller.stopScan();
        mode = "results";
        draw();
      });
      return;
    }

    if (mode === "results") {
      const ranked = [...scanProgress.results].sort((a, b) => (b.videoCount + b.fileCount) - (a.videoCount + a.fileCount));
      const withVideos = ranked.filter((r) => r.videoCount + r.fileCount > 0);
      const zeroVideos = ranked.filter((r) => r.videoCount + r.fileCount === 0);
      const visible = showZero ? ranked : withVideos;

      const wrap = document.createElement("div");
      wrap.className = "tg-results";
      wrap.innerHTML = `<h2>Manage chats</h2><p>Select chats to show in My Telegram chats.</p>`;
      const list = document.createElement("div");
      list.className = "tg-chat-list";

      visible.forEach((r, idx) => {
        const btn = document.createElement("button");
        btn.className = "focusable tg-chat-item";
        if (idx === 0) btn.setAttribute("data-autofocus", "1");
        const checked = host.controller.isChosen(r.chat.id);
        btn.textContent = `${checked ? "[x]" : "[ ]"} ${r.chat.title} — ${formatCountLabel(r)}`;
        btn.addEventListener("click", () => {
          host.controller.toggleChat(r.chat);
          draw();
        });
        list.appendChild(btn);
      });

      wrap.appendChild(list);

      if (!showZero && zeroVideos.length > 0) {
        const more = document.createElement("button");
        more.className = "focusable";
        more.textContent = `Show ${zeroVideos.length} chats without videos`;
        more.addEventListener("click", () => { showZero = true; draw(); });
        wrap.appendChild(more);
      }

      const doneBtn = document.createElement("button");
      doneBtn.className = "focusable";
      doneBtn.textContent = "Done";
      doneBtn.addEventListener("click", () => {
        mode = host.controller.getDoc().chosenChats.length > 0 ? "home" : "prompt";
        draw();
      });
      wrap.appendChild(doneBtn);
      container.appendChild(wrap);
      return;
    }

    if (mode === "advanced") {
      const opts = doc.options;
      const wrap = document.createElement("div");
      wrap.className = "tg-advanced";
      wrap.innerHTML = `<h2>Advanced settings</h2>`;

      const lengths: Array<{ label: string; sec: number }> = [
        { label: "5 min", sec: 300 },
        { label: "10 min", sec: 600 },
        { label: "15 min", sec: 900 },
        { label: "30 min", sec: 1800 },
        { label: "Any length", sec: 0 },
      ];
      lengths.forEach((l, i) => {
        const b = document.createElement("button");
        b.className = "focusable";
        if (i === 0) b.setAttribute("data-autofocus", "1");
        b.textContent = `${opts.minDurationSeconds === l.sec ? "* " : ""}${l.label}`;
        b.addEventListener("click", () => {
          host.controller.updateOptions({ minDurationSeconds: l.sec });
          draw();
        });
        wrap.appendChild(b);
      });

      const personal = document.createElement("button");
      personal.className = "focusable";
      personal.textContent = `Personal chats: ${opts.includePersonalChats ? "On" : "Off"}`;
      personal.addEventListener("click", () => {
        host.controller.updateOptions({ includePersonalChats: !opts.includePersonalChats });
        draw();
      });
      wrap.appendChild(personal);

      const saved = document.createElement("button");
      saved.className = "focusable";
      saved.textContent = `Saved Messages: ${opts.includeSavedMessages ? "On" : "Off"}`;
      saved.addEventListener("click", () => {
        host.controller.updateOptions({ includeSavedMessages: !opts.includeSavedMessages });
        draw();
      });
      wrap.appendChild(saved);

      const back = document.createElement("button");
      back.className = "focusable";
      back.textContent = "Back";
      back.addEventListener("click", () => { mode = "home"; draw(); });
      wrap.appendChild(back);
      container.appendChild(wrap);
      return;
    }

    if (mode === "chat" && activeChat) {
      const wrap = document.createElement("div");
      wrap.className = "tg-chat-videos";
      wrap.innerHTML = `<h2>${activeChat.title}</h2>${statusMsg ? `<p class="tg-status">${statusMsg}</p>` : ""}`;

      const grid = document.createElement("div");
      grid.className = "tg-video-grid";
      const libIds = host.inLibraryIds();
      videos.forEach((v, idx) => {
        const card = document.createElement("button");
        card.className = "focusable tg-video-card";
        if (idx === 0) card.setAttribute("data-autofocus", "1");
        const inLib = v.remoteUniqueId ? libIds.has(v.remoteUniqueId) : false;
        card.innerHTML = `
          <div class="tg-video-title">${v.title}</div>
          <div class="tg-video-meta">${[formatDuration(v.durationSeconds), formatSize(v.sizeBytes), inLib ? "In library" : ""].filter(Boolean).join(" · ")}</div>
        `;
        card.addEventListener("click", () => host.onPlayVideo(v));
        card.addEventListener("contextmenu", async (e) => {
          e.preventDefault();
          if (v.protectedContent) {
            statusMsg = "This chat doesn't allow saving.";
            draw();
            return;
          }
          if (!host.hasLibraryChannel()) {
            statusMsg = "Connect Telegram in the Cablegram phone app to add videos to your library.";
            draw();
            return;
          }
          await host.controller.addToLibrary(v.chatId, v.messageId);
          await host.onAddedToLibrary(v);
          statusMsg = `Added "${v.title}" to library.`;
          draw();
        });
        grid.appendChild(card);
      });
      wrap.appendChild(grid);

      const backBtn = document.createElement("button");
      backBtn.className = "focusable";
      backBtn.textContent = "Back to My Telegram chats";
      backBtn.addEventListener("click", () => {
        activeChat = null;
        statusMsg = "";
        mode = "home";
        draw();
      });
      wrap.appendChild(backBtn);
      container.appendChild(wrap);
      return;
    }

    // mode === "home"
    const wrap = document.createElement("div");
    wrap.className = "tg-home";
    wrap.innerHTML = `<h2>My Telegram chats</h2>`;
    const row = document.createElement("div");
    row.className = "tg-chats-row";
    doc.chosenChats.forEach((c, idx) => {
      const b = document.createElement("button");
      b.className = "focusable tg-chat-tile";
      if (idx === 0) b.setAttribute("data-autofocus", "1");
      b.textContent = c.title;
      b.addEventListener("click", async () => {
        activeChat = c;
        statusMsg = "";
        videos = await host.controller.loadChatVideos(c.id, 0, 30);
        mode = "chat";
        draw();
      });
      row.appendChild(b);
    });

    const lookAgain = document.createElement("button");
    lookAgain.className = "focusable";
    lookAgain.textContent = "Look again";
    lookAgain.addEventListener("click", () => { mode = "prompt"; draw(); });
    row.appendChild(lookAgain);

    const manage = document.createElement("button");
    manage.className = "focusable";
    manage.textContent = "Manage chats";
    manage.addEventListener("click", () => { mode = "results"; draw(); });
    row.appendChild(manage);

    const adv = document.createElement("button");
    adv.className = "focusable";
    adv.textContent = "Advanced settings";
    adv.addEventListener("click", () => { mode = "advanced"; draw(); });
    row.appendChild(adv);

    wrap.appendChild(row);
    container.appendChild(wrap);
  };

  draw();
}
