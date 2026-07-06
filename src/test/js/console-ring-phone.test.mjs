import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

class ClassList {
  constructor() {
    this.values = new Set();
  }

  add(value) {
    this.values.add(value);
  }

  remove(value) {
    this.values.delete(value);
  }

  toggle(value, enabled) {
    if (enabled) {
      this.values.add(value);
    } else {
      this.values.delete(value);
    }
  }

  contains(value) {
    return this.values.has(value);
  }
}

class Element {
  constructor(tag = 'div', id = '') {
    this.tag = tag;
    this.id = id;
    this.dataset = {};
    this.style = {};
    this.children = [];
    this.listeners = new Map();
    this.classList = new ClassList();
    this.className = '';
    this.textContent = '';
    this.disabled = false;
  }

  setAttribute(name, value) {
    this[name] = String(value);
  }

  addEventListener(type, listener) {
    this.listeners.set(type, listener);
  }

  appendChild(child) {
    child.parentElement = this;
    this.children.push(child);
    return child;
  }

  replaceWith(child) {
    const siblings = this.parentElement?.children || [];
    const index = siblings.indexOf(this);
    if (index >= 0) {
      child.parentElement = this.parentElement;
      siblings[index] = child;
    }
  }

  remove() {
    const siblings = this.parentElement?.children || [];
    const index = siblings.indexOf(this);
    if (index >= 0) {
      siblings.splice(index, 1);
    }
  }

  set innerHTML(value) {
    this._innerHTML = value;
    this.children = [];
    if (value.includes('pane-header')) {
      const title = new Element('button');
      title.className = 'pane-title';
      const state = new Element('span');
      state.className = 'pane-state';
      const body = new Element('section');
      body.className = 'terminal-body';
      this.appendChild(title);
      this.appendChild(state);
      this.appendChild(body);
    }
  }

  get innerHTML() {
    return this._innerHTML || '';
  }

  get lastElementChild() {
    return this.children[this.children.length - 1] || null;
  }

  querySelector(selector) {
    if (selector === 'iframe') return null;
    if (selector.startsWith('.')) {
      const className = selector.slice(1);
      return this.children.find(child => child.className.split(' ').includes(className)) || null;
    }
    return null;
  }

  querySelectorAll() {
    return [];
  }
}

function loadConsole(fetchImpl) {
  const elements = new Map();
  const ids = [
    'sessionList', 'paneGrid', 'errorBanner', 'newSessionButton',
    'ringPhoneButton', 'syncBlfButton', 'ringPhoneStatus', 'syncStatus', 'sidebarResizer'
  ];
  for (const id of ids) {
    elements.set(id, new Element('div', id));
  }
  elements.get('paneGrid').classList = new ClassList();
  const app = new Element('main');
  elements.set('sessionsTitle', new Element('h1', 'sessionsTitle'));
  elements.set('cockpitTitle', new Element('h2', 'cockpitTitle'));
  elements.set('cockpitSubtitle', new Element('p', 'cockpitSubtitle'));
  const layoutButtons = [1, 2, 3, 4, 6].map(count => {
    const button = new Element('button');
    button.className = 'layout-button';
    button.dataset.paneCount = String(count);
    return button;
  });
  const localeButtons = ['zh-CN', 'en'].map(locale => {
    const button = new Element('button');
    button.className = 'locale-button';
    button.dataset.locale = locale;
    return button;
  });
  const document = {
    documentElement: {},
    body: new Element('body'),
    title: '',
    getElementById: id => elements.get(id),
    querySelector: selector => selector === '.app' ? app : null,
    querySelectorAll: selector => {
      if (selector === '.layout-button') return layoutButtons;
      if (selector === '.locale-button') return localeButtons;
      return [];
    },
    createElement: tag => new Element(tag),
    addEventListener: () => {}
  };
  const localStorage = {
    values: new Map(),
    getItem(key) {
      return this.values.get(key) || null;
    },
    setItem(key, value) {
      this.values.set(key, String(value));
    }
  };
  const context = {
    document,
    localStorage,
    fetch: fetchImpl,
    navigator: {language: 'zh-CN', languages: ['zh-CN']},
    setInterval: () => 0,
    console,
    Number,
    Date,
    JSON,
    Math,
    String,
    Error,
    Map,
    Set,
    Array
  };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync('src/main/resources/static/console/console.js', 'utf8'), context, {
    filename: 'src/main/resources/static/console/console.js'
  });
  return {context, elements};
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return {promise, resolve, reject};
}

test('global Ring Phone success toggles ringing state and posts to API', async () => {
  const calls = [];
  const {context, elements} = loadConsole(async (url, options = {}) => {
    calls.push([url, options.method || 'GET']);
    if (url === '/api/codex-sessions') {
      return {ok: true, json: async () => []};
    }
    return {ok: true, json: async () => ({attemptId: 'ring-1', status: 'STARTED'})};
  });

  await context.ringPhone();

  assert.deepEqual(calls.at(-1), ['/api/ring-phone', 'POST']);
  assert.equal(elements.get('ringPhoneButton').disabled, false);
  assert.equal(elements.get('ringPhoneButton').textContent, '呼叫座机');
  assert.equal(elements.get('ringPhoneStatus').textContent, '已呼叫座机');
});

test('global Ring Phone failure restores button and shows error', async () => {
  const {context, elements} = loadConsole(async (url, options = {}) => {
    if (url === '/api/codex-sessions') {
      return {ok: true, json: async () => []};
    }
    return {ok: false, json: async () => ({errorMessage: 'busy'})};
  });

  await context.ringPhone();

  assert.equal(elements.get('ringPhoneButton').disabled, false);
  assert.equal(elements.get('ringPhoneButton').textContent, '呼叫座机');
  assert.equal(elements.get('errorBanner').textContent, '呼叫座机失败，请检查电话注册和 Asterisk 状态');
  assert.equal(elements.get('ringPhoneStatus').textContent, '呼叫座机失败，请检查电话注册和 Asterisk 状态');
});

test('Sync BLF success posts to manual sync API and shows red/green guidance', async () => {
  const calls = [];
  const {context, elements} = loadConsole(async (url, options = {}) => {
    calls.push([url, options.method || 'GET']);
    if (url === '/api/codex-sessions') {
      return {ok: true, json: async () => []};
    }
    return {ok: true, json: async () => ({success: true, slots: []})};
  });

  await context.syncBlf();

  assert.deepEqual(calls.filter(call => call[0] === '/internal/admin/blf/sync'), [
    ['/internal/admin/blf/sync', 'POST']
  ]);
  assert.equal(elements.get('syncBlfButton').disabled, false);
  assert.equal(elements.get('syncBlfButton').textContent, '同步 BLF');
  assert.equal(
    elements.get('ringPhoneStatus').textContent,
    'BLF 状态已刷新。请检查座机 BLF 灯：占用任务应为红灯，空闲键应为绿灯。'
  );
  assert.equal(elements.get('ringPhoneStatus').textContent.includes('红灯'), true);
  assert.equal(elements.get('ringPhoneStatus').textContent.includes('绿灯'), true);
});

test('Sync BLF button click is wired to the manual sync action', async () => {
  const calls = [];
  const {elements} = loadConsole(async (url, options = {}) => {
    calls.push([url, options.method || 'GET']);
    if (url === '/api/codex-sessions') {
      return {ok: true, json: async () => []};
    }
    return {ok: true, json: async () => ({success: true})};
  });

  await elements.get('syncBlfButton').listeners.get('click')();

  assert.deepEqual(calls.filter(call => call[0] === '/internal/admin/blf/sync'), [
    ['/internal/admin/blf/sync', 'POST']
  ]);
  assert.equal(elements.get('ringPhoneStatus').textContent.includes('红灯'), true);
});

test('Sync BLF non-2xx failure restores button and hides backend details', async () => {
  const {context, elements} = loadConsole(async url => {
    if (url === '/api/codex-sessions') {
      return {ok: true, json: async () => []};
    }
    return {ok: false, json: async () => ({errorMessage: 'AMI permission denied'})};
  });

  await context.syncBlf();

  assert.equal(elements.get('syncBlfButton').disabled, false);
  assert.equal(elements.get('syncBlfButton').textContent, '同步 BLF');
  assert.equal(elements.get('errorBanner').textContent, 'BLF 同步失败。请确认座机已注册、Eventlist BLF 已订阅，然后重试。');
  assert.equal(elements.get('ringPhoneStatus').textContent, 'BLF 同步失败。请确认座机已注册、Eventlist BLF 已订阅，然后重试。');
  assert.equal(elements.get('errorBanner').textContent.includes('AMI'), false);
});

test('Sync BLF success false is handled as a friendly failure', async () => {
  const {context, elements} = loadConsole(async url => {
    if (url === '/api/codex-sessions') {
      return {ok: true, json: async () => []};
    }
    return {ok: true, json: async () => ({success: false, error: 'FAILED_BLF_NOTIFY'})};
  });

  await context.syncBlf();

  assert.equal(elements.get('syncBlfButton').disabled, false);
  assert.equal(elements.get('errorBanner').textContent, 'BLF 同步失败。请确认座机已注册、Eventlist BLF 已订阅，然后重试。');
  assert.equal(elements.get('errorBanner').textContent.includes('FAILED_BLF_NOTIFY'), false);
});

test('Sync BLF fetch throw restores button and shows a friendly failure', async () => {
  const {context, elements} = loadConsole(async url => {
    if (url === '/api/codex-sessions') {
      return {ok: true, json: async () => []};
    }
    throw new Error('network reset');
  });

  await context.syncBlf();

  assert.equal(elements.get('syncBlfButton').disabled, false);
  assert.equal(elements.get('errorBanner').textContent, 'BLF 同步失败。请确认座机已注册、Eventlist BLF 已订阅，然后重试。');
  assert.equal(elements.get('errorBanner').textContent.includes('network reset'), false);
});

test('Sync BLF ignores repeated clicks while a sync is in flight', async () => {
  const syncResponse = deferred();
  const calls = [];
  const {context, elements} = loadConsole(async (url, options = {}) => {
    calls.push([url, options.method || 'GET']);
    if (url === '/api/codex-sessions') {
      return {ok: true, json: async () => []};
    }
    return syncResponse.promise;
  });

  const firstSync = context.syncBlf();
  const secondSync = context.syncBlf();
  await Promise.resolve();

  assert.equal(elements.get('syncBlfButton').disabled, true);
  assert.equal(elements.get('syncBlfButton').textContent, '正在同步 BLF...');
  assert.equal(calls.filter(call => call[0] === '/internal/admin/blf/sync').length, 1);

  syncResponse.resolve({ok: true, json: async () => ({success: true})});
  await Promise.all([firstSync, secondSync]);

  assert.equal(elements.get('syncBlfButton').disabled, false);
  assert.equal(calls.filter(call => call[0] === '/internal/admin/blf/sync').length, 1);
});

test('Locale switch localizes Sync BLF copy without triggering sync', async () => {
  const calls = [];
  const {context, elements} = loadConsole(async (url, options = {}) => {
    calls.push([url, options.method || 'GET']);
    if (url === '/api/codex-sessions') {
      return {ok: true, json: async () => []};
    }
    return {ok: true, json: async () => ({success: true})};
  });

  context.setLocale('en');

  assert.equal(elements.get('syncBlfButton').textContent, 'Sync BLF');
  assert.equal(calls.filter(call => call[0] === '/internal/admin/blf/sync').length, 0);

  await context.syncBlf();

  assert.equal(elements.get('ringPhoneStatus').textContent, 'BLF state refreshed. Check the desk phone BLF lamps: active tasks should be red and idle keys should be green.');
  assert.equal(elements.get('ringPhoneStatus').textContent.includes('active tasks'), true);
  assert.equal(elements.get('ringPhoneStatus').textContent.includes('idle keys'), true);
});

test('Ring Phone and Sync BLF loading states do not disable each other', async () => {
  const syncResponse = deferred();
  const ringResponse = deferred();
  const calls = [];
  const {context, elements} = loadConsole(async (url, options = {}) => {
    calls.push([url, options.method || 'GET']);
    if (url === '/api/codex-sessions') {
      return {ok: true, json: async () => []};
    }
    if (url === '/internal/admin/blf/sync') {
      return syncResponse.promise;
    }
    if (url === '/api/ring-phone') {
      return ringResponse.promise;
    }
    return {ok: true, json: async () => ({})};
  });

  const sync = context.syncBlf();
  await Promise.resolve();

  assert.equal(elements.get('syncBlfButton').disabled, true);
  assert.equal(elements.get('ringPhoneButton').disabled, false);

  const ring = context.ringPhone();
  await Promise.resolve();

  assert.equal(elements.get('syncBlfButton').disabled, true);
  assert.equal(elements.get('ringPhoneButton').disabled, true);
  assert.deepEqual(calls.filter(call => call[0] === '/internal/admin/blf/sync'), [
    ['/internal/admin/blf/sync', 'POST']
  ]);
  assert.deepEqual(calls.filter(call => call[0] === '/api/ring-phone'), [
    ['/api/ring-phone', 'POST']
  ]);

  syncResponse.resolve({ok: true, json: async () => ({success: true})});
  ringResponse.resolve({ok: true, json: async () => ({attemptId: 'ring-1', status: 'STARTED'})});
  await Promise.all([sync, ring]);

  assert.equal(elements.get('syncBlfButton').disabled, false);
  assert.equal(elements.get('ringPhoneButton').disabled, false);
});

test('phone reminder action failure hides backend bridge status details', async () => {
  const {context, elements} = loadConsole(async (url, options = {}) => {
    if (url === '/api/codex-sessions') {
      return {ok: true, json: async () => []};
    }
    if (url === '/api/codex-phone-bridges/bridge-1/renotify') {
      return {ok: false, json: async () => ({errorMessage: 'FAILED_TASK_CREATE bridge failed'})};
    }
    return {ok: true, json: async () => ({})};
  });

  await context.submitBridgeAction('bridge-1', 'renotify');

  assert.equal(elements.get('errorBanner').textContent, '再次电话提醒失败，请稍后重试');
  assert.equal(elements.get('errorBanner').textContent.includes('FAILED_TASK_CREATE'), false);
  assert.equal(elements.get('errorBanner').textContent.includes('bridge'), false);
});

test('English locale localizes Ring Phone copy and failure message', async () => {
  const {context, elements} = loadConsole(async (url, options = {}) => {
    if (url === '/api/codex-sessions') {
      return {ok: true, json: async () => []};
    }
    return {ok: false, json: async () => ({errorMessage: 'AMI failed'})};
  });

  context.setLocale('en');
  await context.ringPhone();

  assert.equal(elements.get('ringPhoneButton').textContent, 'Ring phone');
  assert.equal(elements.get('errorBanner').textContent, 'Ring phone failed. Check phone registration and Asterisk status.');
  assert.equal(elements.get('errorBanner').textContent.includes('AMI'), false);
});

test('session load failure explains how to start or diagnose the backend', async () => {
  const {elements} = loadConsole(async () => ({ok: false, status: 404, json: async () => ({})}));

  await new Promise(resolve => setTimeout(resolve, 0));

  assert.equal(
    elements.get('errorBanner').textContent,
    '后端未运行或 API 不可达，请先运行 scripts/phone-agent-dev.sh start；若仍失败，运行 scripts/phone-agent-dev.sh status。'
  );
});

test('English session load failure explains how to start or diagnose the backend', async () => {
  const {context, elements} = loadConsole(async () => ({ok: false, status: 404, json: async () => ({})}));

  context.setLocale('en');
  await new Promise(resolve => setTimeout(resolve, 0));

  assert.equal(
    elements.get('errorBanner').textContent,
    'Backend is not running or the API is unreachable. Run scripts/phone-agent-dev.sh start first; if it still fails, run scripts/phone-agent-dev.sh status.'
  );
});

test('status labels and bridge action visibility are localized by state', () => {
  const {context} = loadConsole(async url => {
    if (url === '/api/codex-sessions') {
      return {ok: true, json: async () => []};
    }
    return {ok: true, json: async () => ({})};
  });

  assert.equal(context.statusLabel('WAITING_USER'), '等待用户');
  assert.equal(context.bridgeText({}), '暂无电话提醒');
  assert.equal(context.sessionBridgeActions({}).children.filter(child => child.tag === 'button').length, 0);

  const cancellable = context.sessionBridgeActions({
    activeBridge: {bridgeId: 'bridge-cancel', status: 'NOTIFIED', phase: 'IN_PROGRESS', cancellable: true}
  });
  assert.deepEqual(
    cancellable.children.filter(child => child.tag === 'button').map(button => button.dataset.bridgeAction),
    ['cancel']
  );

  const renotifyable = context.sessionBridgeActions({
    activeBridge: {bridgeId: 'bridge-renotify', status: 'FAILED_ASR', phase: 'FAILED', renotifyAllowed: true}
  });
  assert.deepEqual(
    renotifyable.children.filter(child => child.tag === 'button').map(button => button.dataset.bridgeAction),
    ['renotify']
  );

  context.setLocale('en');
  assert.equal(context.statusLabel('WAITING_USER'), 'Waiting user');
  assert.equal(context.bridgeText({}), 'No phone reminder');
});
