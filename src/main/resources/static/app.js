"use strict";
const $ = (id) => document.getElementById(id);
const state = {
  csrf: null,
  pairs: [],
  packages: [],
  entries: [],
  selected: new Set(),
  page: 0,
  card: null,
  revealed: false,
  busy: false,
  pendingCard: false,
  register: false,
  previewImport: null,
  importContext: 0,
  deleting: false,
  swapping: false,
  context: 0,
  catalogContext: 0,
  view: "learn",
};
let statusTimer;
function notify(message, error = false) {
  clearTimeout(statusTimer);
  $("status").textContent = message;
  $("status").classList.toggle("error", error);
  $("status").hidden = false;
  if (!error)
    statusTimer = setTimeout(() => {
      $("status").hidden = true;
    }, 7000);
}
async function csrf() {
  const response = await fetch("/api/auth/csrf", { cache: "no-store" });
  if (!response.ok) throw Error("Could not connect. Please reload.");
  state.csrf = await response.json();
}
async function api(path, options = {}) {
  const method = options.method || "GET";
  const headers = new Headers(options.headers);
  if (method !== "GET") {
    if (!state.csrf) await csrf();
    headers.set(state.csrf.headerName, state.csrf.token);
  }
  if (options.json !== undefined) {
    headers.set("Content-Type", "application/json");
    options.body = JSON.stringify(options.json);
  }
  const response = await fetch(path, {
    ...options,
    headers,
    cache: "no-store",
  });
  if (!response.ok) {
    let message =
      response.status === 401
        ? "Please log in to continue."
        : response.status === 403
          ? "Your session changed. Reload and log in again."
          : `Request failed (${response.status}).`;
    try {
      const data = await response.json();
      if (data.message) message = data.message;
    } catch {
      /* Non-JSON server errors use the status message. */
    }
    if (response.status === 401 && path !== "/api/auth/login") showAuth();
    if (response.status === 401 && path === "/api/auth/login")
      message = "Invalid username or password.";
    throw Error(message);
  }
  return response.status === 204 ||
    response.headers.get("content-length") === "0"
    ? null
    : response.text().then((text) => (text ? JSON.parse(text) : null));
}
function on(id, event, fn) {
  $(id).addEventListener(event, async (e) => {
    try {
      await fn(e);
    } catch (error) {
      notify(error.message, true);
    }
  });
}
function formValues(form) {
  return Object.fromEntries(new FormData(form));
}
function option(value, text) {
  const el = document.createElement("option");
  el.value = value;
  el.textContent = text;
  return el;
}
function pairId() {
  return Number($("pair-select").value);
}
function packageId() {
  return $("package-select").value ? Number($("package-select").value) : null;
}
function pair() {
  return state.pairs.find((item) => item.ID === pairId());
}
function clearPreview() {
  state.previewImport = null;
  state.importContext++;
  $("import-preview").hidden = true;
  $("commit-import").disabled = true;
}
function updateCsvDirection() {
  const p = pair();
  if (!p) return;
  const forward = $("csv-direction").value === "FORWARD";
  const source = forward ? p.SOURCE_LANGUAGE : p.TARGET_LANGUAGE;
  const target = forward ? p.TARGET_LANGUAGE : p.SOURCE_LANGUAGE;
  $("csv-direction-help").textContent =
    `The source column contains ${source}; the target column contains ${target}.`;
}
function resetCard() {
  state.context++;
  state.card = null;
  state.revealed = false;
  $("flashcard-wrap").hidden = true;
  $("card-placeholder").hidden = false;
  $("card-placeholder").querySelector("h3").textContent = "Your next card";
  $("card-placeholder").querySelector("p").textContent =
    "Cards load automatically. You can also check for cards again.";
  $("start-session").disabled = state.busy;
  $("start-session").textContent = "Check for cards";
}
function showAuth() {
  clearTimeout(statusTimer);
  $("status").hidden = true;
  $("status").textContent = "";
  setAuthMode(false);
  $("auth-form").reset();
  $("settings-form").reset();
  state.view = "learn";
  state.catalogContext++;
  state.pendingCard = false;
  document.querySelectorAll("[data-view]").forEach((tab) => {
    if (tab.dataset.view === "learn") tab.setAttribute("aria-current", "page");
    else tab.removeAttribute("aria-current");
  });
  for (const name of ["learn", "vocabulary", "settings"])
    $(`${name}-view`).hidden = name !== "learn";
  state.context++;
  state.card = null;
  state.selected.clear();
  state.entries = [];
  state.pairs = [];
  state.packages = [];
  $("share-list").replaceChildren();
  $("sharing-panel").hidden = true;
  clearPreview();
  $("auth").hidden = false;
  $("workspace").hidden = true;
  $("account").hidden = true;
}
async function loadWorkspace() {
  const user = await api("/api/auth/me");
  await csrf();
  $("username").textContent = user.username;
  $("auth").hidden = true;
  $("workspace").hidden = false;
  $("account").hidden = false;
  await loadPairs();
}
async function loadPairs(preferred) {
  state.pairs = await api("/api/pairs");
  const previous = preferred || pairId();
  $("pair-select").replaceChildren(
    ...state.pairs.map((p) =>
      option(
        p.ID,
        `${p.SOURCE_LANGUAGE} ↔ ${p.TARGET_LANGUAGE} (${p.IS_OWNER ? "yours" : "shared by " + p.OWNER_USERNAME})`,
      ),
    ),
  );
  if (state.pairs.some((p) => p.ID === previous))
    $("pair-select").value = previous;
  const empty = !state.pairs.length;
  $("empty-pairs").hidden = !empty;
  $("pair-workspace").hidden = empty;
  $("pair-form").hidden = !empty;
  if (!empty) await loadPair();
}
async function loadPair() {
  resetCard();
  const context = ++state.catalogContext;
  $("package-select").replaceChildren(option("", "All words"));
  clearPreview();
  state.selected.clear();
  state.page = 0;
  const p = pair();
  const own = p.IS_OWNER;
  $("catalog-editor").hidden = !own;
  $("sharing-panel").hidden = !own;
  $("sharing-panel").open = false;
  $("share-form").reset();
  $("share-list").replaceChildren();
  $("sharing-status").textContent = "";
  $("pair-access").textContent = own
    ? "Private unless you share it with selected users."
    : `Shared by ${p.OWNER_USERNAME}. Study these words and packages with your own progress.`;
  $("assign-package").closest("label").hidden = !own;
  $("assign-words").hidden = !own;
  $("delete-words").hidden = !own;
  $("swap-words").hidden = !own;
  $("select-page").closest("th").hidden = !own;
  $("direction").options[0].textContent =
    `${p.SOURCE_LANGUAGE} → ${p.TARGET_LANGUAGE}`;
  $("direction").options[1].textContent =
    `${p.TARGET_LANGUAGE} → ${p.SOURCE_LANGUAGE}`;
  $("csv-direction").replaceChildren(
    option("FORWARD", `${p.SOURCE_LANGUAGE} → ${p.TARGET_LANGUAGE}`),
    option("REVERSE", `${p.TARGET_LANGUAGE} → ${p.SOURCE_LANGUAGE}`),
  );
  updateCsvDirection();
  $("preview-source-heading").textContent = p.SOURCE_LANGUAGE;
  $("preview-target-heading").textContent = p.TARGET_LANGUAGE;
  $("source-label").firstChild.textContent = p.SOURCE_LANGUAGE;
  $("target-label").firstChild.textContent = p.TARGET_LANGUAGE;
  $("source-heading").textContent = p.SOURCE_LANGUAGE;
  $("target-heading").textContent = p.TARGET_LANGUAGE;
  updateDirection();
  await loadPackages();
  if (context !== state.catalogContext) return;
  await Promise.all([
    ensureCard(),
    loadEntries(),
    ...(p.IS_OWNER ? [loadShares()] : []),
  ]);
}
function updateDirection() {
  $("learning-direction").textContent =
    $("direction").selectedOptions[0].textContent;
}
async function loadPackages(preferred = "") {
  const context = state.catalogContext;
  const requestedPair = pairId();
  const packages = await api(`/api/pairs/${requestedPair}/packages`);
  if (context !== state.catalogContext || requestedPair !== pairId()) return;
  state.packages = packages;
  $("package-select").replaceChildren(
    option("", "All words"),
    ...state.packages.map((p) => option(p.ID, `${p.NAME} (${p.ENTRY_COUNT})`)),
  );
  if (state.packages.some((p) => String(p.ID) === String(preferred)))
    $("package-select").value = preferred;
  $("assign-package").replaceChildren(
    option("", "Choose a package"),
    ...state.packages.map((p) => option(p.ID, p.NAME)),
  );
}
async function loadEntries() {
  const context = state.catalogContext;
  const requestedPair = pairId();
  const selectedPackage = packageId();
  const query = selectedPackage ? `?packageId=${selectedPackage}` : "";
  const entries = await api(`/api/pairs/${requestedPair}/entries${query}`);
  if (context !== state.catalogContext || requestedPair !== pairId() || selectedPackage !== packageId()) return;
  state.entries = entries;
  state.selected.clear();
  state.page = 0;
  renderEntries();
  $("remove-words").hidden = !packageId() || !pair().IS_OWNER;
}
function filteredEntries() {
  const query = $("search").value.toLocaleLowerCase().trim();
  return state.entries.filter((e) =>
    `${e.SOURCE_TEXT} ${e.TARGET_TEXT} ${e.NOTES}`
      .toLocaleLowerCase()
      .includes(query),
  );
}
function pageEntries() {
  return filteredEntries().slice(state.page * 50, (state.page + 1) * 50);
}
function cell(text) {
  const td = document.createElement("td");
  td.textContent = text;
  return td;
}
function renderEntries() {
  const filtered = filteredEntries();
  const pages = Math.max(1, Math.ceil(filtered.length / 50));
  state.page = Math.min(state.page, pages - 1);
  $("entry-rows").replaceChildren(
    ...pageEntries().map((entry) => {
      const tr = document.createElement("tr");
      const td = document.createElement("td");
      td.hidden = !pair().IS_OWNER;
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.checked = state.selected.has(entry.ID);
      checkbox.setAttribute("aria-label", `Select ${entry.SOURCE_TEXT}`);
      checkbox.addEventListener("change", () => {
        checkbox.checked
          ? state.selected.add(entry.ID)
          : state.selected.delete(entry.ID);
        updateSelection();
      });
      td.append(checkbox);
      tr.append(
        td,
        cell(entry.SOURCE_TEXT),
        cell(entry.TARGET_TEXT),
        cell(entry.NOTES),
      );
      return tr;
    }),
  );
  if (!filtered.length) {
    const tr = document.createElement("tr");
    const td = cell(
      state.entries.length
        ? "No matching words."
        : "No words here yet. Add a word or import a CSV to get started.",
    );
    td.colSpan = 4;
    tr.append(td);
    $("entry-rows").append(tr);
  }
  $("word-count").textContent = `${state.entries.length} words`;
  $("page-label").textContent = `Page ${state.page + 1} of ${pages}`;
  $("previous-page").disabled = state.page === 0;
  $("next-page").disabled = state.page + 1 >= pages;
  updateSelection();
}
function updateSelection() {
  const entries = pageEntries();
  $("select-page").checked =
    entries.length > 0 && entries.every((e) => state.selected.has(e.ID));
  $("select-page").indeterminate =
    entries.some((e) => state.selected.has(e.ID)) && !$("select-page").checked;
  $("assign-words").textContent = state.selected.size
    ? `Add ${state.selected.size} to package`
    : "Add to package";
  const changingWords = state.deleting || state.swapping;
  $("assign-words").disabled = changingWords || !state.selected.size;
  $("remove-words").disabled = changingWords || !state.selected.size;
  $("swap-words").textContent = state.swapping ? "Swapping…" : "Swap translations";
  $("swap-words").disabled = changingWords || !state.selected.size;
  $("delete-words").textContent = state.deleting
    ? "Deleting…"
    : state.selected.size
      ? `Delete ${state.selected.size} ${state.selected.size === 1 ? "word" : "words"}`
      : "Delete selected words";
  $("delete-words").disabled = changingWords || !state.selected.size;
}
async function ensureCard() {
  if (state.view === "learn" && pair() && !state.card && !$("workspace").hidden)
    await nextCard();
}
async function loadPendingCard() {
  if (!state.pendingCard) return;
  state.pendingCard = false;
  await ensureCard();
}
async function nextCard() {
  if (!pair() || $("workspace").hidden) return;
  if (state.busy) {
    state.pendingCard = true;
    return;
  }
  state.pendingCard = false;
  state.busy = true;
  $("start-session").disabled = true;
  const context = state.context;
  if (!state.card) {
    $("card-placeholder").querySelector("h3").textContent = "Loading your next card…";
    $("card-placeholder").querySelector("p").textContent = "Finding a word in this selection.";
  }
  try {
    const result = await api("/api/learning/next", {
      method: "POST",
      json: {
        pairId: pairId(),
        direction: $("direction").value,
        packageId: packageId(),
      },
    });
    if (context !== state.context) return;
    state.card = result.card;
    state.revealed = false;
    $("active-count").textContent =
      `${result.activeCount} of ${result.activeLimit} places in your active list.`;
    $("flashcard-wrap").hidden = !result.card;
    $("card-placeholder").hidden = !!result.card;
    if (!result.card) {
      $("card-placeholder").querySelector("h3").textContent =
        "You’re caught up";
      $("card-placeholder").querySelector("p").textContent =
        "No words are due in this selection. Add vocabulary, choose another package, or come back later.";
    } else renderCard();
    $("start-session").textContent = "Check for cards";
  } catch (error) {
    if (context === state.context) {
      if (!state.card) {
        $("card-placeholder").querySelector("h3").textContent = "Couldn’t load cards";
        $("card-placeholder").querySelector("p").textContent = "Check your connection, then choose Check for cards to try again.";
      }
      throw error;
    }
  } finally {
    state.busy = false;
    $("start-session").disabled = false;
    await loadPendingCard();
  }
}
function renderCard() {
  const card = state.card;
  if (!card) return;
  const p = pair();
  const forward = $("direction").value === "FORWARD";
  $("card-caption").textContent =
    `${card.maintenance ? "Maintenance review" : "Learning"} · Score ${card.score.toFixed(1)} / 10` +
    (card.correctStreak ? ` · ${card.correctStreak} correct in a row` : "");
  $("card-language").textContent = state.revealed
    ? forward
      ? p.TARGET_LANGUAGE
      : p.SOURCE_LANGUAGE
    : forward
      ? p.SOURCE_LANGUAGE
      : p.TARGET_LANGUAGE;
  $("card-word").textContent = state.revealed ? card.answer : card.prompt;
  $("card-notes").textContent = state.revealed ? card.notes : "";
  $("card-hint").textContent = state.revealed
    ? "How did you do?"
    : "Tap to reveal";
  $("flashcard").classList.toggle("revealed", state.revealed);
  $("flashcard").setAttribute(
    "aria-label",
    state.revealed ? "Show original word" : "Reveal translation",
  );
  $("answer-actions").hidden = !state.revealed;
}
async function review(correct) {
  if (!state.card || !state.revealed || state.busy) return;
  state.busy = true;
  $("again").disabled = $("knew").disabled = true;
  $("start-session").disabled = true;
  const context = state.context;
  try {
    const result = await api("/api/learning/review", {
      method: "POST",
      json: { ticket: state.card.ticket, correct },
    });
    if (context !== state.context) return;
    notify(
      `${correct ? "Remembered" : "Keep practicing"} · Score ${result.after.toFixed(1)} / 10` +
        (correct && result.correctStreak > 1
          ? ` · ${result.correctStreak} in a row (+${(result.after - result.before).toFixed(1)})`
          : ""),
    );
    state.card = null;
  } catch (error) {
    if (context === state.context) resetCard();
    throw error;
  } finally {
    state.busy = false;
    $("again").disabled = $("knew").disabled = false;
    $("start-session").disabled = false;
    if (context !== state.context) await loadPendingCard();
  }
  if (context === state.context) await nextCard();
}
function setAuthMode(register) {
  state.register = register;
  $("auth-title").textContent = state.register
    ? "Make yourself at home"
    : "Welcome back";
  $("auth-description").textContent = state.register
    ? "Choose a username and a password of at least 10 characters."
    : "Log in to pick up your next card.";
  $("password").autocomplete = state.register
    ? "new-password"
    : "current-password";
  $("password").minLength = state.register ? 10 : 1;
  $("auth-submit").textContent = state.register ? "Create account" : "Log in";
  $("auth-toggle").textContent = state.register
    ? "Already have an account? Log in"
    : "New here? Create an account";
}
on("auth-toggle", "click", () => setAuthMode(!state.register));
on("auth-form", "submit", async (e) => {
  e.preventDefault();
  $("auth-submit").disabled = true;
  try {
    const values = formValues(e.target);
    values.username = values.username.toLowerCase();
    if (state.register)
      await api("/api/auth/register", { method: "POST", json: values });
    await api("/api/auth/login", {
      method: "POST",
      body: new URLSearchParams(values),
    });
    e.target.reset();
    $("status").hidden = true;
    await loadWorkspace();
  } finally {
    $("auth-submit").disabled = false;
  }
});
on("logout", "click", async () => {
  await api("/api/auth/logout", { method: "POST" });
  showAuth();
  await csrf();
});
on("show-pair", "click", () => {
  $("pair-form").hidden = false;
  $("pair-form").elements.sourceLanguage.focus();
});
on("cancel-pair", "click", () => {
  $("pair-form").hidden = true;
});
on("pair-form", "submit", async (e) => {
  e.preventDefault();
  const values = formValues(e.target);
  await api("/api/pairs", { method: "POST", json: values });
  e.target.reset();
  await loadPairs();
  const added = state.pairs.find(
    (p) =>
      p.IS_OWNER &&
      p.SOURCE_LANGUAGE === values.sourceLanguage.trim() &&
      p.TARGET_LANGUAGE === values.targetLanguage.trim(),
  );
  if (added) {
    $("pair-select").value = added.ID;
    await loadPair();
  }
  notify("Language pair created. Add your first words in Vocabulary.");
});
on("pair-select", "change", loadPair);
on("direction", "change", async () => {
  resetCard();
  updateDirection();
  await ensureCard();
});
on("package-select", "change", async () => {
  resetCard();
  clearPreview();
  await ensureCard();
  await loadEntries();
});
document.querySelectorAll("[data-view]").forEach((button) =>
  button.addEventListener("click", async () => {
    try {
      state.view = button.dataset.view;
      document.querySelectorAll("[data-view]").forEach((tab) => {
        if (tab === button) tab.setAttribute("aria-current", "page");
        else tab.removeAttribute("aria-current");
      });
      for (const name of ["learn", "vocabulary", "settings"])
        $(`${name}-view`).hidden = name !== state.view;
      if (state.view === "learn") await ensureCard();
      if (state.view === "settings") {
        const settings = await api("/api/learning/settings");
        for (const [key, value] of Object.entries(settings))
          $("settings-form").elements[key].value = value;
      }
    } catch (error) {
      notify(error.message, true);
    }
  }),
);
async function loadShares() {
  const requestedPair = pairId();
  const context = state.context;
  const shares = await api(`/api/pairs/${requestedPair}/shares`);
  if (
    context !== state.context ||
    requestedPair !== pairId() ||
    !pair()?.IS_OWNER
  )
    return;
  $("sharing-status").textContent = shares.length
    ? `Shared with ${shares.length} ${shares.length === 1 ? "user" : "users"}.`
    : "Only you can access this language pair.";
  $("share-list").replaceChildren(
    ...shares.map((share) => {
      const li = document.createElement("li");
      const name = document.createElement("span");
      name.textContent = share.USERNAME;
      const stop = document.createElement("button");
      stop.type = "button";
      stop.className = "quiet";
      stop.textContent = "Stop sharing";
      stop.setAttribute("aria-label", `Stop sharing with ${share.USERNAME}`);
      stop.addEventListener("click", async () => {
        stop.disabled = true;
        try {
          await api(`/api/pairs/${requestedPair}/shares/${share.ID}`, {
            method: "DELETE",
          });
          if (requestedPair === pairId() && pair()?.IS_OWNER) {
            await loadShares();
            notify(`Stopped sharing with ${share.USERNAME}.`);
          }
        } catch (error) {
          notify(error.message, true);
        } finally {
          stop.disabled = false;
        }
      });
      li.append(name, stop);
      return li;
    }),
  );
}
on("share-form", "submit", async (event) => {
  event.preventDefault();
  const requestedPair = pairId();
  const context = state.context;
  $("share-submit").disabled = true;
  try {
    await api(`/api/pairs/${requestedPair}/shares`, {
      method: "PUT",
      json: formValues(event.target),
    });
    if (context !== state.context || requestedPair !== pairId()) return;
    event.target.reset();
    await loadShares();
    notify("Vocabulary shared with the selected user.");
  } finally {
    $("share-submit").disabled = false;
  }
});
on("entry-form", "submit", async (e) => {
  e.preventDefault();
  await api(`/api/pairs/${pairId()}/entries`, {
    method: "POST",
    json: formValues(e.target),
  });
  e.target.reset();
  resetCard();
  await loadEntries();
  notify("Word added to the collection.");
});
on("package-form", "submit", async (e) => {
  e.preventDefault();
  const selected = packageId();
  await api(`/api/pairs/${pairId()}/packages`, {
    method: "POST",
    json: formValues(e.target),
  });
  e.target.reset();
  await loadPackages(selected);
  notify("Package created. Select words below to add them.");
});
on("search", "input", () => {
  state.page = 0;
  renderEntries();
});
on("select-page", "change", () => {
  pageEntries().forEach((entry) =>
    $("select-page").checked
      ? state.selected.add(entry.ID)
      : state.selected.delete(entry.ID),
  );
  renderEntries();
});
on("previous-page", "click", () => {
  state.page--;
  renderEntries();
});
on("next-page", "click", () => {
  state.page++;
  renderEntries();
});
on("assign-words", "click", async () => {
  const destination = Number($("assign-package").value);
  if (!destination) throw Error("Choose a destination package first.");
  for (const entryId of state.selected)
    await api(
      `/api/pairs/${pairId()}/packages/${destination}/entries/${entryId}`,
      { method: "PUT" },
    );
  const selected = packageId();
  await loadPackages(selected);
  await loadEntries();
  notify("Words added to the package.");
});
on("remove-words", "click", async () => {
  for (const entryId of state.selected)
    await api(
      `/api/pairs/${pairId()}/packages/${packageId()}/entries/${entryId}`,
      { method: "DELETE" },
    );
  const selected = packageId();
  await loadPackages(selected);
  await loadEntries();
  resetCard();
  notify("Words removed from the package.");
});
on("swap-words", "click", async () => {
  if (state.swapping || state.deleting || !state.selected.size || !pair()?.IS_OWNER) return;
  const requestedPair = pairId();
  const ids = [...state.selected];
  const p = pair();
  if (!window.confirm(
    `Swap the ${p.SOURCE_LANGUAGE} and ${p.TARGET_LANGUAGE} text for ${ids.length} selected ${ids.length === 1 ? "word" : "words"}? ` +
    "Notes and packages stay attached. Learning progress will follow the same question and answer.",
  )) return;
  state.swapping = true;
  updateSelection();
  clearPreview();
  try {
    await api(`/api/pairs/${requestedPair}/entries/swap`, {
      method: "POST",
      json: { entryIds: ids },
    });
    if (!pair() || requestedPair !== pairId()) return;
    resetCard();
    await loadEntries();
    notify(`Swapped translations for ${ids.length} ${ids.length === 1 ? "word" : "words"}.`);
  } finally {
    state.swapping = false;
    if (pair()) updateSelection();
  }
});
on("delete-words", "click", async () => {
  if (state.deleting || state.swapping || !state.selected.size || !pair()?.IS_OWNER) return;
  const requestedPair = pairId();
  const ids = [...state.selected];
  const p = pair();
  if (!window.confirm(
    `Delete ${ids.length} ${ids.length === 1 ? "word" : "words"} from ${p.SOURCE_LANGUAGE} / ${p.TARGET_LANGUAGE}? ` +
    "This removes them from all packages and deletes everyone's learning progress and review history for these words. This cannot be undone.",
  )) return;
  state.deleting = true;
  updateSelection();
  clearPreview();
  try {
    await api(`/api/pairs/${requestedPair}/entries`, {
      method: "DELETE",
      json: { entryIds: ids },
    });
    if (!pair() || requestedPair !== pairId()) return;
    resetCard();
    const selected = packageId();
    await loadPackages(selected);
    await loadEntries();
    notify(`Deleted ${ids.length} ${ids.length === 1 ? "word" : "words"} from the collection.`);
  } finally {
    state.deleting = false;
    if (pair()) updateSelection();
  }
});
on("csv-file", "change", clearPreview);
on("csv-direction", "change", () => {
  clearPreview();
  updateCsvDirection();
});
async function importCsv(request, commit) {
  const form = new FormData();
  form.append("file", request.file);
  form.append("commit", commit);
  form.append("direction", request.direction);
  if (request.packageId) form.append("packageId", request.packageId);
  return api(`/api/pairs/${request.pairId}/import`, { method: "POST", body: form });
}
on("import-form", "submit", async (e) => {
  e.preventDefault();
  clearPreview();
  const file = $("csv-file").files[0];
  if (!file) throw Error("Choose a CSV file.");
  if (file.size > 2 * 1024 * 1024)
    throw Error("Choose a CSV smaller than 2 MB.");
  const request = {
    file,
    direction: $("csv-direction").value,
    pairId: pairId(),
    packageId: packageId(),
  };
  const context = state.importContext;
  const result = await importCsv(request, false);
  if (context !== state.importContext) return;
  state.previewImport = request;
  $("import-summary").textContent =
    `${result.newEntries} new words, ${result.duplicates} duplicates skipped, ${result.errors} invalid rows. Preview shows the language pair's saved order.`;
  $("import-errors").replaceChildren(
    ...result.rows
      .filter((row) => row.error)
      .slice(0, 20)
      .map((row) => {
        const li = document.createElement("li");
        li.textContent = `Row ${row.row}: ${row.error}`;
        return li;
      }),
  );
  $("preview-rows").replaceChildren(
    ...result.rows.slice(0, 20).map((row) => {
      const tr = document.createElement("tr");
      tr.append(
        cell(row.row),
        cell(row.source),
        cell(row.target),
        cell(row.error || (row.duplicate ? "Already exists" : "Ready to import")),
      );
      return tr;
    }),
  );
  $("commit-import").disabled = result.errors > 0;
  $("import-preview").hidden = false;
});
on("commit-import", "click", async () => {
  const request = state.previewImport;
  if (!request) return;
  const context = state.importContext;
  $("commit-import").disabled = true;
  try {
    const result = await importCsv(request, true);
    if (context !== state.importContext) return;
    clearPreview();
    $("csv-file").value = "";
    const selected = packageId();
    await loadPackages(selected);
    await loadEntries();
    resetCard();
    notify(`Imported ${result.newEntries} new words. ${result.duplicates} duplicates skipped.`);
  } finally {
    if (context === state.importContext)
      $("commit-import").disabled = !state.previewImport;
  }
});
on("settings-form", "submit", async (e) => {
  e.preventDefault();
  const values = formValues(e.target);
  for (const key of Object.keys(values)) values[key] = Number(values[key]);
  await api("/api/learning/settings", { method: "PUT", json: values });
  resetCard();
  notify("Learning settings saved.");
});
on("start-session", "click", nextCard);
on("flashcard", "click", () => {
  if (state.card && !state.busy) {
    state.revealed = !state.revealed;
    renderCard();
  }
});
on("again", "click", () => review(false));
on("knew", "click", () => review(true));
let pointerStart = null;
let suppressClick = false;
$("flashcard").addEventListener("pointerdown", (event) => {
  pointerStart = { x: event.clientX, y: event.clientY };
});
$("flashcard").addEventListener("pointercancel", () => {
  pointerStart = null;
});
$("flashcard").addEventListener("pointerup", async (event) => {
  if (!pointerStart) return;
  const dx = event.clientX - pointerStart.x;
  const dy = event.clientY - pointerStart.y;
  pointerStart = null;
  if (
    state.revealed &&
    Math.abs(dx) > 65 &&
    Math.abs(dx) > Math.abs(dy) * 1.5
  ) {
    suppressClick = true;
    try {
      await review(dx > 0);
    } catch (error) {
      notify(error.message, true);
    }
  }
});
$("flashcard").addEventListener(
  "click",
  (event) => {
    if (suppressClick) {
      suppressClick = false;
      event.stopImmediatePropagation();
    }
  },
  true,
);
function connection() {
  $("connection-status").textContent = navigator.onLine
    ? ""
    : "Offline · Connect to review or edit vocabulary.";
}
window.addEventListener("online", connection);
window.addEventListener("offline", connection);
connection();
(async () => {
  try {
    await csrf();
    const response = await fetch("/api/auth/me", { cache: "no-store" });
    if (response.ok) await loadWorkspace();
    else if (response.status !== 401)
      throw Error("Could not load your account. Reload to try again.");
  } catch (error) {
    notify(error.message, true);
  }
  if ("serviceWorker" in navigator)
    navigator.serviceWorker.register("/sw.js").catch(() => {
      /* Online use still works without installation. */
    });
})();
