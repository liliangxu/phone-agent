const MAX_PANES = 6;
const SUPPORTED_PANE_COUNTS = [1, 2, 3, 4, 6];
const RENOTIFY_STATUSES = new Set([
  'FAILED_TASK_CREATE',
  'FAILED_BLF_NOTIFY',
  'FAILED_RECORDING',
  'FAILED_ASR',
  'FAILED_CODEX_SESSION_STOPPED',
  'FAILED_REPLY_TO_CODEX',
  'CANCELLED',
  'NO_REPLY',
  'REPLIED_TO_CODEX'
]);
const MESSAGES = {
  'zh-CN': {
    title: 'Phone Agent 控制台',
    sessionsTitle: '会话',
    newSession: '新建',
    startingSession: '正在启动...',
    cockpitTitle: 'Codex 工作台',
    cockpitSubtitle: '把会话拖入面板。',
    paneLayout: '面板布局',
    syncLive: '同步中',
    syncError: '同步异常',
    noSessions: '暂无会话。',
    created: '创建',
    updated: '更新',
    emptyPane: '空面板',
    dropSession: '拖入会话',
    dropSessionHere: '把 Codex 会话拖到这里。',
    startingTerminal: '正在恢复终端...',
    terminalRestoreFailed: '终端暂时无法恢复。',
    sessionCreateFailed: '创建会话失败。',
    sessionLoadFailed: '加载会话失败。',
    backendUnavailable: '后端未运行或 API 不可达，请先运行 scripts/phone-agent-dev.sh start；若仍失败，运行 scripts/phone-agent-dev.sh status。',
    terminalCreateFailed: '会话创建失败。',
    terminalNotReady: '终端尚未就绪。',
    ringPhoneIdle: '可用于提醒座机用户查看红色按键',
    ringPhoneButton: '呼叫座机',
    ringPhoneLoading: '正在呼叫座机...',
    ringPhoneSuccess: '已呼叫座机',
    ringPhoneFailure: '呼叫座机失败，请检查电话注册和 Asterisk 状态',
    syncBlfButton: '同步 BLF',
    syncBlfLoading: '正在同步 BLF...',
    syncBlfSuccess: 'BLF 状态已刷新。请检查座机 BLF 灯：占用任务应为红灯，空闲键应为绿灯。',
    syncBlfFailure: 'BLF 同步失败。请确认座机已注册、Eventlist BLF 已订阅，然后重试。',
    bridgeActionFailed: '电话提醒操作失败，请稍后重试',
    status: {
      IDLE: '空闲',
      CREATING: '创建中',
      RUNNING: '运行中',
      WAITING_USER: '等待用户',
      COMPLETED: '已完成',
      CREATE_FAILED: '创建失败',
      UNKNOWN: '未知状态'
    },
    bridge: {
      NONE: '暂无电话提醒',
      IN_PROGRESS: '等待电话回复',
      DONE: '电话回复已处理',
      FAILED: '电话提醒异常，请稍后重试',
      CANCELLED: '电话提醒已取消',
      cancel: '取消提醒',
      renotify: '再次提醒',
      canceling: '正在取消...',
      renotifying: '正在再次提醒...',
      cancelFailed: '取消电话提醒失败，请稍后重试',
      renotifyFailed: '再次电话提醒失败，请稍后重试'
    }
  },
  en: {
    title: 'Phone Agent Console',
    sessionsTitle: 'Sessions',
    newSession: 'New',
    startingSession: 'Starting...',
    cockpitTitle: 'Codex Cockpit',
    cockpitSubtitle: 'Drag sessions into panes.',
    paneLayout: 'Pane layout',
    syncLive: 'Live',
    syncError: 'Sync error',
    noSessions: 'No sessions yet.',
    created: 'Created',
    updated: 'Updated',
    emptyPane: 'Empty',
    dropSession: 'Drop session',
    dropSessionHere: 'Drop a Codex session here.',
    startingTerminal: 'Starting terminal...',
    terminalRestoreFailed: 'Terminal could not be restored.',
    sessionCreateFailed: 'Failed to create session.',
    sessionLoadFailed: 'Failed to load sessions.',
    backendUnavailable: 'Backend is not running or the API is unreachable. Run scripts/phone-agent-dev.sh start first; if it still fails, run scripts/phone-agent-dev.sh status.',
    terminalCreateFailed: 'Session creation failed.',
    terminalNotReady: 'Terminal is not ready yet.',
    ringPhoneIdle: 'Use this to remind the desk phone user to check the red key.',
    ringPhoneButton: 'Ring phone',
    ringPhoneLoading: 'Ringing phone...',
    ringPhoneSuccess: 'Phone ring sent',
    ringPhoneFailure: 'Ring phone failed. Check phone registration and Asterisk status.',
    syncBlfButton: 'Sync BLF',
    syncBlfLoading: 'Syncing BLF...',
    syncBlfSuccess: 'BLF state refreshed. Check the desk phone BLF lamps: active tasks should be red and idle keys should be green.',
    syncBlfFailure: 'BLF sync failed. Confirm the phone is registered and Eventlist BLF is subscribed, then retry.',
    bridgeActionFailed: 'Phone reminder action failed. Please try again later.',
    status: {
      IDLE: 'Idle',
      CREATING: 'Creating',
      RUNNING: 'Running',
      WAITING_USER: 'Waiting user',
      COMPLETED: 'Completed',
      CREATE_FAILED: 'Create failed',
      UNKNOWN: 'Unknown status'
    },
    bridge: {
      NONE: 'No phone reminder',
      IN_PROGRESS: 'Waiting for phone reply',
      DONE: 'Phone reply handled',
      FAILED: 'Phone reminder failed. Please try again later.',
      CANCELLED: 'Phone reminder cancelled',
      cancel: 'Cancel reminder',
      renotify: 'Remind again',
      canceling: 'Canceling...',
      renotifying: 'Reminding again...',
      cancelFailed: 'Failed to cancel phone reminder. Please try again later.',
      renotifyFailed: 'Failed to remind by phone again. Please try again later.'
    }
  }
};

const state = {
  sessions: [],
  selectedId: null,
  activePane: Number(localStorage.getItem('phoneAgent.activePane') || 0),
  panes: loadPanes(),
  bridgeActionInFlight: new Set(),
  terminalEnsureInFlight: new Set(),
  terminalEnsuredUrls: new Map(),
  creatingSession: false,
  ringingPhone: false,
  syncingBlf: false,
  syncState: 'live',
  locale: resolveLocale()
};

const app = document.querySelector('.app');
const sessionList = document.getElementById('sessionList');
const paneGrid = document.getElementById('paneGrid');
const errorBanner = document.getElementById('errorBanner');
const newSessionButton = document.getElementById('newSessionButton');
const ringPhoneButton = document.getElementById('ringPhoneButton');
const syncBlfButton = document.getElementById('syncBlfButton');
const ringPhoneStatus = document.getElementById('ringPhoneStatus');
const syncStatus = document.getElementById('syncStatus');
const sidebarResizer = document.getElementById('sidebarResizer');
const sessionsTitle = document.getElementById('sessionsTitle');
const cockpitTitle = document.getElementById('cockpitTitle');
const cockpitSubtitle = document.getElementById('cockpitSubtitle');
const layoutButtons = Array.from(document.querySelectorAll('.layout-button'));
const localeButtons = Array.from(document.querySelectorAll('.locale-button'));

newSessionButton.addEventListener('click', createDefaultSession);
ringPhoneButton.addEventListener('click', ringPhone);
syncBlfButton.addEventListener('click', syncBlf);
sidebarResizer.addEventListener('pointerdown', startSidebarResize);
for (const button of layoutButtons) {
  button.addEventListener('click', () => setPaneCount(Number(button.dataset.paneCount)));
}
for (const button of localeButtons) {
  button.addEventListener('click', () => setLocale(button.dataset.locale));
}

paneGrid.addEventListener('dragover', event => {
  event.preventDefault();
  paneGrid.classList.add('drop-target');
});
paneGrid.addEventListener('dragleave', event => {
  if (!paneGrid.contains(event.relatedTarget)) {
    paneGrid.classList.remove('drop-target');
  }
});
document.addEventListener('dragend', clearSessionDragging);
document.addEventListener('drop', clearSessionDragging);

async function createDefaultSession() {
  if (state.creatingSession) return;
  state.creatingSession = true;
  newSessionButton.disabled = true;
  newSessionButton.textContent = t('startingSession');
  try {
    const response = await fetch('/api/codex-sessions', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({})
    });
    const body = await response.json();
    if (!response.ok) {
      throw new Error(body.errorMessage || t('sessionCreateFailed'));
    }
    hideError();
    await refreshSessions();
    placeSessionInPane(body.id);
  } catch (error) {
    showError(error.message);
  } finally {
    state.creatingSession = false;
    newSessionButton.disabled = false;
    newSessionButton.textContent = t('newSession');
  }
}

async function ringPhone() {
  if (state.ringingPhone) return;
  state.ringingPhone = true;
  renderRingPhone();
  try {
    const response = await fetch('/api/ring-phone', {
      method: 'POST',
      headers: {'Accept': 'application/json'}
    });
    if (!response.ok) {
      await responseErrorMessage(response, t('ringPhoneFailure'));
      throw new Error(t('ringPhoneFailure'));
    }
    hideError();
    ringPhoneStatus.textContent = t('ringPhoneSuccess');
  } catch (error) {
    showError(t('ringPhoneFailure'));
    ringPhoneStatus.textContent = t('ringPhoneFailure');
  } finally {
    state.ringingPhone = false;
    renderRingPhone();
  }
}

function renderRingPhone() {
  ringPhoneButton.disabled = state.ringingPhone;
  ringPhoneButton.textContent = state.ringingPhone ? t('ringPhoneLoading') : t('ringPhoneButton');
  ringPhoneButton.classList.toggle('ringing', state.ringingPhone);
  if (state.ringingPhone) {
    ringPhoneStatus.textContent = t('ringPhoneLoading');
  }
}

/**
 * Triggers the existing manual BLF sync endpoint without surfacing backend or
 * AMI details to operators. A separate in-memory loading flag prevents duplicate
 * sync requests while keeping Ring Phone usable during the same interval.
 */
async function syncBlf() {
  if (state.syncingBlf) return;
  state.syncingBlf = true;
  renderBlfSync();
  try {
    const response = await fetch('/internal/admin/blf/sync', {
      method: 'POST',
      headers: {'Accept': 'application/json'}
    });
    const body = await parseJsonResponse(response);
    if (!response.ok || body?.success === false) {
      throw new Error(t('syncBlfFailure'));
    }
    hideError();
    ringPhoneStatus.textContent = t('syncBlfSuccess');
  } catch (error) {
    showError(t('syncBlfFailure'));
    ringPhoneStatus.textContent = t('syncBlfFailure');
  } finally {
    state.syncingBlf = false;
    renderBlfSync();
  }
}

function renderBlfSync() {
  syncBlfButton.disabled = state.syncingBlf;
  syncBlfButton.textContent = state.syncingBlf ? t('syncBlfLoading') : t('syncBlfButton');
  syncBlfButton.classList.toggle('syncing', state.syncingBlf);
  if (state.syncingBlf) {
    ringPhoneStatus.textContent = t('syncBlfLoading');
  }
}

async function refreshSessions() {
  try {
    const response = await fetch('/api/codex-sessions', {cache: 'no-store'});
    if (!response.ok) {
      throw new Error(t('backendUnavailable'));
    }
    state.sessions = (await response.json()).sort(compareSessionsByUpdatedAt);
    updateSyncStatus('live');
    hideError();
    reconcilePaneAssignments();
    ensureSelectedSession();
    renderSessions();
    renderPanes();
  } catch (error) {
    updateSyncStatus('error');
    showError(error.message || t('backendUnavailable'));
  }
}

function renderSessions() {
  if (!state.sessions.length) {
    sessionList.innerHTML = `<div class="placeholder">${escapeHtml(t('noSessions'))}</div>`;
    return;
  }
  sessionList.innerHTML = '';
  for (const session of state.sessions) {
    const card = document.createElement('article');
    card.tabIndex = 0;
    card.draggable = true;
    card.className = 'session' + (session.id === state.selectedId ? ' active' : '');
    card.dataset.sessionId = session.id;
    card.innerHTML = `
      <div class="session-heading">
        ${sessionBridgeDot(session)}
        <span class="status ${escapeHtml(session.status || 'IDLE')}" title="${escapeHtml(session.status || 'IDLE')}">${escapeHtml(statusLabel(session.status))}</span>
        <span class="bridge-inline-status">${escapeHtml(bridgeText(session))}</span>
      </div>
      <div class="session-meta">${escapeHtml(t('created'))} ${escapeHtml(formatClockTime(session.createdAt))}</div>
      <div class="session-meta">${escapeHtml(t('updated'))} ${escapeHtml(formatClockTime(session.updatedAt))}</div>
      ${lastMessageSummary(session) ? `<div class="session-message">${escapeHtml(lastMessageSummary(session).slice(0, 180))}</div>` : ''}
    `;
    card.appendChild(sessionBridgeActions(session));
    card.addEventListener('click', event => {
      event.preventDefault();
      selectSession(session.id);
    });
    card.addEventListener('keydown', event => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        selectSession(session.id);
      }
    });
    card.addEventListener('dragstart', event => {
      event.dataTransfer.setData('text/plain', session.id);
      event.dataTransfer.effectAllowed = 'move';
      startSessionDragging();
    });
    card.addEventListener('dragend', clearSessionDragging);
    sessionList.appendChild(card);
  }
}

function startSessionDragging() {
  // Occupied panes are covered by ttyd iframes, so the parent page must take
  // drag hit-testing back only while a sidebar session is being dragged.
  document.body.classList.add('dragging-session');
}

function clearSessionDragging() {
  document.body.classList.remove('dragging-session');
  paneGrid.classList.remove('drop-target');
  for (const pane of paneGrid.querySelectorAll('.terminal-pane.drag-over')) {
    pane.classList.remove('drag-over');
  }
}

function renderPanes() {
  const paneCount = Math.max(1, state.panes.length);
  paneGrid.className = `pane-grid layout-${paneCount}`;
  renderLayoutButtons(paneCount);

  for (let index = 0; index < state.panes.length; index++) {
    const sessionId = state.panes[index];
    let pane = paneGrid.children[index];
    if (!pane || !pane.classList.contains('terminal-pane') || pane.dataset.sessionId !== String(sessionId || '')) {
      const replacement = buildPane(index, sessionId);
      if (pane) {
        pane.replaceWith(replacement);
      } else {
        paneGrid.appendChild(replacement);
      }
      pane = replacement;
    }
    pane.dataset.paneIndex = String(index);
    pane.dataset.sessionId = String(sessionId || '');
    pane.classList.toggle('active', index === state.activePane);
    wirePane(pane);
    updatePane(pane, index, sessionById(sessionId));
  }
  while (paneGrid.children.length > state.panes.length) {
    paneGrid.lastElementChild.remove();
  }
}

function buildPane(index, sessionId) {
  const pane = document.createElement('article');
  pane.className = 'terminal-pane';
  pane.dataset.paneIndex = String(index);
  pane.dataset.sessionId = String(sessionId || '');
  pane.innerHTML = `
    <header class="pane-header">
      <button type="button" class="pane-title"></button>
      <span class="pane-state"></span>
    </header>
    <section class="terminal-body"></section>
  `;
  return pane;
}

function wirePane(pane) {
  if (pane.dataset.wired === 'true') return;
  pane.dataset.wired = 'true';
  pane.addEventListener('click', () => activatePane(Number(pane.dataset.paneIndex)));
  pane.addEventListener('dragover', event => {
    event.preventDefault();
    pane.classList.add('drag-over');
  });
  pane.addEventListener('dragleave', () => pane.classList.remove('drag-over'));
  pane.addEventListener('drop', event => {
    event.preventDefault();
    event.stopPropagation();
    const sessionId = event.dataTransfer.getData('text/plain');
    clearSessionDragging();
    if (sessionId) {
      setPane(Number(pane.dataset.paneIndex), sessionId);
    }
  });
  pane.querySelector('.pane-title').addEventListener('click', event => {
    event.stopPropagation();
    activatePane(Number(pane.dataset.paneIndex));
  });
}

function updatePane(pane, index, session) {
  const title = pane.querySelector('.pane-title');
  const stateLabel = pane.querySelector('.pane-state');
  if (!session) {
    title.textContent = t('emptyPane');
    stateLabel.textContent = t('dropSession');
    setPanePlaceholder(pane, t('dropSessionHere'));
    return;
  }
  title.textContent = `${session.id} · ${statusLabel(session.status)}`;
  stateLabel.textContent = statusLabel(session.status);
  updatePaneTerminal(pane, index, session);
}

function updatePaneTerminal(pane, index, session) {
  const body = pane.querySelector('.terminal-body');
  if (session.status === 'CREATE_FAILED') {
    setPanePlaceholder(pane, session.errorMessage || terminalMessage(session.status));
    return;
  }
  if (session.ttydUrl && state.terminalEnsuredUrls.get(session.id) === session.ttydUrl) {
    const existing = body.querySelector('iframe');
    if (existing && existing.dataset.sessionId === session.id && existing.dataset.ttydUrl === session.ttydUrl) {
      return;
    }
    body.innerHTML = '';
    const iframe = document.createElement('iframe');
    iframe.src = session.ttydUrl;
    iframe.dataset.sessionId = session.id;
    iframe.dataset.ttydUrl = session.ttydUrl;
    body.appendChild(iframe);
    return;
  }
  setPanePlaceholder(pane, t('startingTerminal'));
  ensurePaneTerminal(index, session.id);
}

async function ensurePaneTerminal(index, sessionId) {
  if (state.terminalEnsureInFlight.has(sessionId)) return;
  state.terminalEnsureInFlight.add(sessionId);
  try {
    const response = await fetch(`/api/codex-sessions/${encodeURIComponent(sessionId)}/terminal/ensure`, {
      method: 'POST',
      headers: {'Accept': 'application/json'}
    });
    if (!response.ok) {
      throw new Error(await responseErrorMessage(response, t('terminalRestoreFailed')));
    }
    const session = await response.json();
    upsertSession(session);
    if (session.ttydUrl) {
      state.terminalEnsuredUrls.set(session.id, session.ttydUrl);
    }
    hideError();
    if (state.panes[index] === sessionId) {
      renderSessions();
      renderPanes();
    }
  } catch (error) {
    if (state.panes[index] === sessionId) {
      state.panes[index] = null;
      persistPanes();
      renderSessions();
      renderPanes();
    }
    showError(error.message);
  } finally {
    state.terminalEnsureInFlight.delete(sessionId);
  }
}

function setPanePlaceholder(pane, message) {
  const body = pane.querySelector('.terminal-body');
  const currentIframe = body.querySelector('iframe');
  if (currentIframe) {
    body.innerHTML = '';
  }
  if (body.textContent.trim() !== message) {
    body.innerHTML = `<div class="placeholder">${escapeHtml(message)}</div>`;
  }
}

function setPane(index, sessionId) {
  if (!sessionById(sessionId) || index < 0 || index >= state.panes.length) return;
  const from = state.panes.indexOf(sessionId);
  // A session may appear in only one pane. Dragging it to an occupied pane
  // replaces the target pane and clears the previous source pane.
  if (from >= 0 && from !== index) {
    state.panes[from] = null;
  }
  state.panes[index] = sessionId;
  state.activePane = index;
  state.selectedId = sessionId;
  persistPanes();
  renderSessions();
  renderPanes();
}

function placeSessionInPane(sessionId) {
  if (!sessionById(sessionId)) return;
  const existing = state.panes.indexOf(sessionId);
  if (existing >= 0) {
    activatePane(existing);
    return;
  }
  const firstEmpty = state.panes.findIndex(id => !id);
  if (firstEmpty >= 0) {
    setPane(firstEmpty, sessionId);
    return;
  }
  setPane(state.activePane, sessionId);
}

function setPaneCount(count) {
  const nextCount = SUPPORTED_PANE_COUNTS.includes(count) ? count : nearestSupportedPaneCount(count);
  while (state.panes.length < nextCount) {
    state.panes.push(null);
  }
  if (state.panes.length > nextCount) {
    state.panes = state.panes.slice(0, nextCount);
  }
  state.activePane = Math.max(0, Math.min(state.activePane, nextCount - 1));
  persistPanes();
  renderSessions();
  renderPanes();
}

function nearestSupportedPaneCount(count) {
  return SUPPORTED_PANE_COUNTS.reduce((best, candidate) => (
    Math.abs(candidate - count) < Math.abs(best - count) ? candidate : best
  ), 1);
}

function activatePane(index) {
  if (index < 0 || index >= state.panes.length) return;
  state.activePane = index;
  if (state.panes[index]) {
    state.selectedId = state.panes[index];
  }
  persistPanes();
  renderSessions();
  renderPanes();
}

function selectSession(id) {
  if (!sessionById(id)) return;
  const paneIndex = state.panes.indexOf(id);
  if (paneIndex >= 0) {
    activatePane(paneIndex);
    return;
  }
  state.selectedId = id;
  renderSessions();
}

function sessionBridgeDot(session) {
  const bridge = bridgeForPanel(session);
  const phase = bridge ? (bridge.phase || bridgePhase(bridge.status)) : (session.phoneBridgeErrorCode ? 'FAILED' : 'NONE');
  const tooltip = bridgeTooltip(phase, bridge, session);
  return `<span class="bridge-dot-wrap" tabindex="0" aria-label="${escapeHtml(tooltip)}"><span class="bridge-dot bridge-dot-${escapeHtml(phase)}" title="${escapeHtml(tooltip)}"></span><span class="bridge-tooltip">${escapeHtml(tooltip)}</span></span>`;
}

function bridgeTooltip(phase, bridge, session) {
  if (!bridge && session?.phoneBridgeErrorCode) {
    return t('bridge.FAILED');
  }
  return t(`bridge.${phase || 'NONE'}`);
}

function bridgePhase(status) {
  if (!status) return 'NONE';
  if (status === 'CANCELLED') return 'CANCELLED';
  if (status === 'REPLIED_TO_CODEX' || status === 'NO_REPLY') return 'DONE';
  if (status.startsWith('FAILED_')) return 'FAILED';
  return 'IN_PROGRESS';
}

function lastMessageSummary(session) {
  return session.lastMessageSummary || session.lastAssistantMessage || '';
}

function sessionBridgeActions(session) {
  const bridge = bridgeForPanel(session);
  const actions = document.createElement('div');
  actions.className = 'session-bridge-actions';
  const status = document.createElement('div');
  status.className = 'session-meta bridge-action-status';
  status.textContent = bridgeText(session);
  actions.appendChild(status);
  if (bridge && bridge.cancellable) {
    actions.appendChild(bridgeActionButton(t('bridge.cancel'), bridge, 'cancel'));
  }
  if (bridge && (bridge.renotifyAllowed || RENOTIFY_STATUSES.has(bridge.status))) {
    actions.appendChild(bridgeActionButton(t('bridge.renotify'), bridge, 'renotify'));
  }
  return actions;
}

function bridgeForPanel(session) {
  return session.activeBridge || latestBridge(session);
}

function latestBridge(session) {
  return Array.isArray(session.bridgeHistory) && session.bridgeHistory.length ? session.bridgeHistory[0] : null;
}

function bridgeActionButton(label, bridge, action) {
  const button = document.createElement('button');
  button.type = 'button';
  button.dataset.bridgeAction = action;
  const loading = bridge && state.bridgeActionInFlight.has(bridgeActionKey(bridge.bridgeId, action));
  button.textContent = loading ? loadingLabel(action) : label;
  button.disabled = loading;
  if (bridge) {
    button.dataset.bridgeId = bridge.bridgeId;
    button.addEventListener('click', event => {
      event.stopPropagation();
      submitBridgeAction(bridge.bridgeId, action);
    });
  }
  return button;
}

async function submitBridgeAction(bridgeId, action) {
  const key = bridgeActionKey(bridgeId, action);
  state.bridgeActionInFlight.add(key);
  renderSessions();
  try {
    const response = await fetch(`/api/codex-phone-bridges/${encodeURIComponent(bridgeId)}/${action}`, {
      method: 'POST',
      headers: {'Accept': 'application/json'}
    });
    if (!response.ok) {
      await responseErrorMessage(response, bridgeActionFailureMessage(action));
      throw new Error(bridgeActionFailureMessage(action));
    }
    hideError();
    await refreshSessions();
  } catch (error) {
    const message = bridgeActionFailureMessage(action);
    await refreshSessions();
    showError(message);
  } finally {
    state.bridgeActionInFlight.delete(key);
    renderSessions();
  }
}

function startSidebarResize(event) {
  event.preventDefault();
  sidebarResizer.setPointerCapture(event.pointerId);
  const move = moveEvent => {
    const width = Math.min(560, Math.max(220, moveEvent.clientX));
    app.style.gridTemplateColumns = `${width}px 6px minmax(0, 1fr)`;
    localStorage.setItem('phoneAgent.sidebarWidth', String(width));
  };
  const up = () => {
    sidebarResizer.removeEventListener('pointermove', move);
    sidebarResizer.removeEventListener('pointerup', up);
  };
  sidebarResizer.addEventListener('pointermove', move);
  sidebarResizer.addEventListener('pointerup', up);
}

function loadPanes() {
  try {
    const parsed = JSON.parse(localStorage.getItem('phoneAgent.panes') || '[]');
    if (Array.isArray(parsed)) {
      const panes = parsed.slice(0, MAX_PANES).map(value => value || null);
      return panes.length ? panes : [null];
    }
    if (parsed && typeof parsed === 'object') {
      const panes = Object.values(parsed).slice(0, MAX_PANES).map(value => value || null);
      return panes.length ? panes : [null];
    }
  } catch (error) {
    return [null];
  }
  return [null];
}

function persistPanes() {
  localStorage.setItem('phoneAgent.panes', JSON.stringify(state.panes.slice(0, MAX_PANES).map(id => id || null)));
  localStorage.setItem('phoneAgent.activePane', String(state.activePane));
}

function reconcilePaneAssignments() {
  const ids = new Set(state.sessions.map(session => session.id));
  state.panes = state.panes.slice(0, MAX_PANES).map(id => ids.has(id) ? id : null);
  if (!state.panes.length) {
    state.panes = [null];
  }
  if (state.activePane >= state.panes.length) {
    state.activePane = Math.max(0, state.panes.length - 1);
  }
  if (state.selectedId && !ids.has(state.selectedId)) {
    state.selectedId = state.panes[state.activePane] || null;
  }
  persistPanes();
}

function ensureSelectedSession() {
  if (!state.sessions.length) {
    state.selectedId = null;
    return;
  }
  if (state.selectedId && sessionById(state.selectedId)) {
    return;
  }
  state.selectedId = state.panes[state.activePane]
    || state.sessions.find(session => session.activeBridge)?.id
    || state.sessions.find(session => latestBridge(session))?.id
    || state.sessions[0].id;
}

function renderLayoutButtons(paneCount) {
  for (const button of layoutButtons) {
    const count = Number(button.dataset.paneCount);
    button.classList.toggle('active', count === paneCount);
    button.setAttribute('aria-pressed', String(count === paneCount));
  }
}

function sessionById(id) {
  return state.sessions.find(session => session.id === id) || null;
}

function upsertSession(session) {
  const index = state.sessions.findIndex(item => item.id === session.id);
  if (index >= 0) {
    state.sessions[index] = session;
  } else {
    state.sessions.push(session);
  }
  state.sessions.sort(compareSessionsByUpdatedAt);
}

async function responseErrorMessage(response, fallback) {
  try {
    const body = await response.json();
    return body.errorMessage || body.message || fallback;
  } catch (error) {
    return fallback;
  }
}

async function parseJsonResponse(response) {
  try {
    return await response.json();
  } catch (error) {
    return null;
  }
}

function bridgeActionKey(bridgeId, action) {
  return `${bridgeId}:${action}`;
}

function loadingLabel(action) {
  return action === 'cancel' ? t('bridge.canceling') : t('bridge.renotifying');
}

function bridgeActionFailureMessage(action) {
  if (action === 'cancel') return t('bridge.cancelFailed');
  if (action === 'renotify') return t('bridge.renotifyFailed');
  return t('bridgeActionFailed');
}

function terminalMessage(status) {
  if (status === 'CREATE_FAILED') return t('terminalCreateFailed');
  return t('terminalNotReady');
}

function showError(message) {
  errorBanner.textContent = message;
  errorBanner.classList.remove('hidden');
}

function hideError() {
  errorBanner.classList.add('hidden');
  errorBanner.textContent = '';
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function formatTime(value) {
  if (!value) return '-';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}

function formatClockTime(value) {
  if (!value) return '-';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleTimeString();
}

function updateSyncStatus(syncState = state.syncState) {
  if (!syncStatus) return;
  state.syncState = syncState;
  const label = syncState === 'error' ? t('syncError') : t('syncLive');
  syncStatus.textContent = `${label} · ${formatClockTime(new Date().toISOString())}`;
}

function renderStaticText() {
  if (document.documentElement) {
    document.documentElement.lang = state.locale;
  }
  document.title = t('title');
  if (sessionsTitle) sessionsTitle.textContent = t('sessionsTitle');
  if (cockpitTitle) cockpitTitle.textContent = t('cockpitTitle');
  if (cockpitSubtitle) cockpitSubtitle.textContent = t('cockpitSubtitle');
  for (const button of layoutButtons) {
    button.setAttribute('aria-label', `${t('paneLayout')} ${button.dataset.paneCount}`);
  }
  for (const button of localeButtons) {
    const active = button.dataset.locale === state.locale;
    button.classList.toggle('active', active);
    button.setAttribute('aria-pressed', String(active));
  }
  newSessionButton.textContent = state.creatingSession ? t('startingSession') : t('newSession');
  if (!state.ringingPhone && !state.syncingBlf) {
    ringPhoneStatus.textContent = t('ringPhoneIdle');
  }
  renderRingPhone();
  renderBlfSync();
  updateSyncStatus();
}

function setLocale(locale) {
  const next = normalizeLocale(locale);
  if (state.locale === next) return;
  state.locale = next;
  localStorage.setItem('phoneAgent.locale', next);
  renderStaticText();
  renderSessions();
  renderPanes();
}

function resolveLocale() {
  const saved = localStorage.getItem('phoneAgent.locale');
  if (saved) return normalizeLocale(saved);
  const browserLanguage = typeof navigator === 'undefined' ? 'en' : (navigator.languages?.[0] || navigator.language || 'en');
  return normalizeLocale(browserLanguage);
}

function normalizeLocale(locale) {
  return String(locale || '').toLowerCase().startsWith('zh') ? 'zh-CN' : 'en';
}

function t(path) {
  const bundle = MESSAGES[state.locale] || MESSAGES.en;
  const fallback = MESSAGES.en;
  return lookup(bundle, path) ?? lookup(fallback, path) ?? path;
}

function lookup(source, path) {
  return path.split('.').reduce((current, part) => (
    current && Object.prototype.hasOwnProperty.call(current, part) ? current[part] : undefined
  ), source);
}

function statusLabel(status) {
  const key = status || 'IDLE';
  return t(`status.${key}`) === `status.${key}` ? `${t('status.UNKNOWN')} (${key})` : t(`status.${key}`);
}

function bridgeText(session) {
  const bridge = bridgeForPanel(session);
  const phase = bridge ? (bridge.phase || bridgePhase(bridge.status)) : (session?.phoneBridgeErrorCode ? 'FAILED' : 'NONE');
  return bridgeTooltip(phase, bridge, session);
}

function compareSessionsByUpdatedAt(left, right) {
  return timestamp(right.updatedAt) - timestamp(left.updatedAt);
}

function timestamp(value) {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? 0 : parsed.getTime();
}

const savedSidebarWidth = Number(localStorage.getItem('phoneAgent.sidebarWidth'));
if (savedSidebarWidth) {
  app.style.gridTemplateColumns = `${Math.min(560, Math.max(220, savedSidebarWidth))}px 6px minmax(0, 1fr)`;
}
renderStaticText();
renderPanes();
refreshSessions();
setInterval(refreshSessions, 2000);
