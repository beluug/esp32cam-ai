import storage from '@system.storage'

const STORAGE_KEY = 'bandbridge_text_state_v2'
const MAX_HISTORY_COUNT = 30

const state = {
  latestText: 'Waiting for phone text...',
  history: [],
  currentStatus: 'Waiting',
  selectedHistoryItem: null
}

let storageLoaded = false

function nowLabel() {
  const date = new Date()
  const pad = value => (value < 10 ? '0' + value : '' + value)
  return pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds())
}

function saveState() {
  storage.set({
    key: STORAGE_KEY,
    value: JSON.stringify(state)
  })
}

function ensureLoaded(done) {
  if (storageLoaded) {
    done()
    return
  }

  storage.get({
    key: STORAGE_KEY,
    default: '',
    success(data) {
      if (data) {
        try {
          const parsed = JSON.parse(data)
          state.latestText = parsed.latestText || state.latestText
          state.history = parsed.history || []
          state.currentStatus = parsed.currentStatus || state.currentStatus
          state.selectedHistoryItem = parsed.selectedHistoryItem || null
        } catch (error) {
          console.error('load state parse failed', error)
        }
      }
      storageLoaded = true
      done()
    },
    fail() {
      storageLoaded = true
      done()
    }
  })
}

export function loadBandState(callback) {
  ensureLoaded(() => {
    callback({
      latestText: state.latestText,
      history: state.history.slice(),
      currentStatus: state.currentStatus
    })
  })
}

export function setCurrentStatus(status) {
  ensureLoaded(() => {
    state.currentStatus = status || 'Waiting'
    saveState()
  })
}

export function updateLiveMessage(message) {
  ensureLoaded(() => {
    const content = message && message.content ? String(message.content).trim() : ''
    if (!content) {
      return
    }

    state.latestText = content
    state.currentStatus = message && message.title ? String(message.title) : 'Receiving'
    saveState()
  })
}

export function saveReceivedMessage(message) {
  ensureLoaded(() => {
    const content = message && message.content ? String(message.content).trim() : ''
    if (!content) {
      return
    }

    const title = message && message.title ? String(message.title) : nowLabel()
    const item = {
      id: String(Date.now()),
      title: title,
      content: content
    }

    state.latestText = content
    state.currentStatus = 'Receive success'
    state.history.unshift(item)
    state.history = state.history.slice(0, MAX_HISTORY_COUNT)
    saveState()
  })
}

export function deleteHistoryItem(id) {
  ensureLoaded(() => {
    state.history = state.history.filter(item => item.id !== id)
    saveState()
  })
}

export function selectHistoryItemById(id, callback) {
  ensureLoaded(() => {
    state.selectedHistoryItem = state.history.find(item => item.id === id) || null
    saveState()
    if (callback) {
      callback(state.selectedHistoryItem)
    }
  })
}

export function loadSelectedHistoryItem(callback) {
  ensureLoaded(() => {
    callback(state.selectedHistoryItem)
  })
}
