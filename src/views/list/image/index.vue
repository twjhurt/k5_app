<template>
  <div class="container">
    <Breadcrumb :items="[$t('menu.list'), $t('menu.image')]" />
    <a-row :gutter="20" align="stretch">
      <a-col :span="24">
        <a-spin :loading="state.loading || state.reading" :tip="state.reading ? $t('image.reading') : $t('image.writing')" style="width: 100%;">
          <a-card class="general-card" :title="$t('menu.image') + $t('global.onStart')">
            <div id="canvasDiv" style="zoom: 250%; display: none"></div>
            <div
              ref="fsHost"
              class="pixel-editor-host"
              :class="{ 'is-fs': state.isFs }"
            >
              <div v-if="state.isFs" class="pixel-fs-input"
                @pointerdown="onPointerDown"
                @pointermove="onPointerMove"
                @pointerup="onPointerUp"
                @pointercancel="onPointerCancel"
                @contextmenu.prevent
              ></div>
              <div class="pixel-fs-bar" v-if="state.isFs">
                <span class="pixel-fs-title">{{ $t('image.fsBar') }}</span>
                <t-button size="small" theme="default" variant="outline" @click.stop="exitFullscreen">{{ $t('image.fsExit') }}</t-button>
              </div>
              <div
                class="pixel-matrix-wrap"
                :class="{ 'is-entry': !state.isFs }"
              >
                <button
                  type="button"
                  class="pixel-fs-hint"
                  v-if="!state.isFs && showFsHint"
                  @click.stop="enterFullscreen"
                >
                  {{ $t('image.fsHint') }}
                </button>
                <div class="pixel-matrix-holder">
                  <div
                    class="pixel-matrix"
                    role="img"
                    :aria-label="'128x64'"
                    @pointerdown="onPointerDown"
                    @pointermove="onPointerMove"
                    @pointerup="onPointerUp"
                    @pointercancel="onPointerCancel"
                    @contextmenu.prevent
                  >
                    <template v-for="(col, y) in state.matrix" :key="y">
                      <div
                        v-for="(row, x) in col"
                        :key="x + '-' + y"
                        class="pixel-cell"
                        :style="{ backgroundColor: row }"
                      ></div>
                    </template>
                  </div>
                  <div
                    v-if="state.isFs && state.matrix.length"
                    class="pixel-pen"
                    :class="{ 'is-down': state.penDown }"
                    :style="penStyle"
                  ></div>
                </div>
                <div v-if="state.isFs" class="pixel-fs-zones" aria-hidden="true">
                  <div class="pixel-fs-zone pixel-fs-zone--draw" :class="{ 'is-active': state.penDown }">
                    <span>{{ $t('image.fsZoneDraw') }}</span>
                  </div>
                  <div class="pixel-fs-zone pixel-fs-zone--move" :class="{ 'is-active': state.penActive }">
                    <span>{{ $t('image.fsZoneMove') }}</span>
                  </div>
                </div>
              </div>
            </div>
            <div class="pixel-editor-actions">
              <t-button
                v-if="!state.isFs"
                size="small"
                theme="primary"
                variant="outline"
                @click.stop="enterFullscreen"
              >
                {{ $t('image.fsEnter') }}
              </t-button>
            </div>
            <br>
            {{ $t('image.threshold') }}<t-slider v-model="state.threshold" :max="256" class="threshold-slider" @change-end="changeThreshold" />
            <br>
            <a-space>
              <a-button :loading="state.reading" @click="readFromDevice">{{ $t('image.readDevice') }}</a-button>
              <a-button @click="selectFile">{{ $t('tool.selectImage') }}</a-button>
              <a-button :disabled="state.matrix.length < 64" @click="negativeIt">{{ $t('image.negative') }}</a-button>
              <a-button :disabled="state.matrix.length < 64" @click="saveIt">{{ $t('cps.save') }}</a-button>
              <a-button type="primary" :disabled="state.matrix.length < 64" @click="flashIt">{{ $t('tool.write') }}</a-button>
            </a-space>
          </a-card>
        </a-spin>
      </a-col>
    </a-row>
    <!-- 任务③：从设备读取的当前开机图片预览 -->
    <a-modal
      v-model:visible="state.previewVisible"
      :title="$t('image.previewTitle')"
      unmount-on-close
    >
      <div style="text-align: center; background: #fff; padding: 8px; border-radius: 4px;">
        <img
          v-if="state.previewUrl"
          :src="state.previewUrl"
          alt="boot image preview"
          style="width: 100%; max-width: 512px; image-rendering: pixelated; display: inline-block;"
        />
      </div>
      <template #footer>
        <a-space>
          <a-button @click="state.previewVisible = false">{{ $t('settings.close') }}</a-button>
          <a-button type="primary" :disabled="!state.previewUrl" @click="savePreviewAs">{{ $t('image.savePreview') }}</a-button>
        </a-space>
      </template>
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
import { reactive, onMounted, onBeforeUnmount, ref, computed } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { Message } from '@arco-design/web-vue';
import { useAppStore } from '@/store';
import {
  eeprom_write,
  eeprom_reboot,
  eeprom_init,
  eeprom_read,
  shared_read,
  shared_write,
  disconnect,
} from '@/utils/serial.js';
import { saveDataUrlAs } from '@/utils/download';

const { t } = useI18n();
const appStore = useAppStore();
const fsHost = ref<HTMLElement | null>(null);

const GRID_W = 128
const GRID_H = 64
const BRUSH = 3
// trackpad-style sensitivity: finger px → cell px
const PEN_GAIN = 1.6

const state : {
  binaryFile: any,
  loading: boolean,
  matrix: any,
  mousedown: boolean,
  threshold: number,
  cache: any,
  isFs: boolean,
  inlinePaint: boolean,
  penX: number,
  penY: number,
  penFX: number,
  penFY: number,
  penActive: boolean,
  penDown: boolean,
  leftPid: number | null,
  rightPid: number | null,
  trackX: number,
  trackY: number,
  reading: boolean,
  previewUrl: string,
  previewVisible: boolean
} = reactive({
  binaryFile: undefined,
  loading: false,
  matrix: [],
  mousedown: false,
  threshold: 128,
  cache: undefined,
  isFs: false,
  inlinePaint: false,
  penX: 64,
  penY: 32,
  penFX: 64,
  penFY: 32,
  penActive: false,
  penDown: false,
  leftPid: null,
  rightPid: null,
  trackX: 0,
  trackY: 0,
  reading: false,
  previewUrl: '',
  previewVisible: false
})

const route = useRoute();

// Desktop mouse paints inline; mobile / coarse pointer opens landscape fullscreen.
const isTouchLike = () => {
  if (typeof window === 'undefined') return false
  if (appStore.device === 'mobile') return true
  return window.matchMedia?.('(pointer: coarse)').matches ?? false
}

const showFsHint = computed(() => !state.isFs && isTouchLike())

const penStyle = computed(() => {
  // 1×1 pixel cursor, centered on the current cell
  return {
    left: `${((state.penX + 0.5) / GRID_W) * 100}%`,
    top: `${((state.penY + 0.5) / GRID_H) * 100}%`,
    width: `${(1 / GRID_W) * 100}%`,
    height: `${(1 / GRID_H) * 100}%`,
    transform: 'translate(-50%, -50%)'
  }
})

const unlockOrientation = () => {
  try {
    (screen.orientation as any)?.unlock?.()
  } catch {}
}

let enteringFs = false

const resetPen = (x = Math.floor(GRID_W / 2), y = Math.floor(GRID_H / 2)) => {
  state.penX = x
  state.penY = y
  state.penFX = x
  state.penFY = y
  state.penActive = false
  state.penDown = false
  state.leftPid = null
  state.rightPid = null
}

const getFsElement = () => fsHost.value

const requestFs = async (el: HTMLElement) => {
  const anyEl = el as any
  const raceTimeout = new Promise((_, reject) =>
    setTimeout(() => reject(new Error('fullscreen-timeout')), 800)
  )
  if (typeof anyEl.requestFullscreen === 'function') {
    await Promise.race([anyEl.requestFullscreen(), raceTimeout])
    return
  }
  if (typeof anyEl.webkitRequestFullscreen === 'function') {
    await Promise.race([anyEl.webkitRequestFullscreen(), raceTimeout])
    return
  }
  throw new Error('fullscreen-unsupported')
}

const isDocumentFullscreen = () =>
  !!(document.fullscreenElement || (document as any).webkitFullscreenElement)

// APK 宿主（WebView 不支持元素级全屏，需要原生伪全屏兜底）
const isApkHost = () => typeof window !== 'undefined' && !!(window as any).K5Native

const enterFullscreen = async () => {
  if (enteringFs || state.isFs) return
  const el = getFsElement()
  if (!el) return
  enteringFs = true
  try {
    // 任务④：安卓 WebView 不支持元素级全屏（无 onShowCustomView），
    // 先试 Web fullscreen 必然失败还可能空等 800ms —— APK 宿主直接进原生伪全屏。
    if (isApkHost()) {
      ;(window as any).__K5USB?.enterAppFullscreen?.()
      state.isFs = true
      resetPen()
      return
    }
    if (!isDocumentFullscreen()) {
      let fsOk = false
      try {
        await requestFs(el)
        fsOk = isDocumentFullscreen()
      } catch (err) {
        fsOk = false
      }
      if (fsOk) {
        try {
          await (screen.orientation as any)?.lock?.('landscape')
        } catch {}
        state.isFs = true
        resetPen()
      } else {
        // 非安卓浏览器全屏失败：退化为页内直接绘制模式。
        state.isFs = false
        state.inlinePaint = true
      }
    }
  } finally {
    if (!state.isFs) {
      if (isDocumentFullscreen()) {
        state.isFs = true
        resetPen()
      } else {
        state.isFs = false
        state.inlinePaint = true
      }
    }
    enteringFs = false
  }
}

const exitFullscreen = async () => {
  try {
    const doc = document as any
    if (document.fullscreenElement) {
      await document.exitFullscreen()
    } else if (doc.webkitFullscreenElement && doc.webkitExitFullscreen) {
      await doc.webkitExitFullscreen()
    }
  } catch {}
  // 任务④：退出原生伪全屏（恢复竖屏/系统栏）。
  ;(window as any).__K5USB?.exitAppFullscreen?.()
  unlockOrientation()
  state.isFs = false
  resetPen(state.penX, state.penY)
}

// 任务④：APK 返回键在伪全屏时触发的退出请求
const onK5ExitFsRequest = () => {
  if (state.isFs) {
    void exitFullscreen()
  }
}

const onFullscreenChange = () => {
  const active = isDocumentFullscreen()
  state.isFs = active
  if (!active) {
    unlockOrientation()
    resetPen(state.penX, state.penY)
  }
}

const cellSize = () => {
  const host = fsHost.value
  const board = host?.querySelector('.pixel-matrix') as HTMLElement | null
  if (!board) return null
  const rect = board.getBoundingClientRect()
  const cellW = rect.width / GRID_W
  const cellH = rect.height / GRID_H
  if (cellW <= 0 || cellH <= 0) return null
  return { rect, cellW, cellH }
}

const cellFromPoint = (clientX: number, clientY: number) => {
  const size = cellSize()
  if (!size) return null
  const x = Math.floor((clientX - size.rect.left) / size.cellW)
  const y = Math.floor((clientY - size.rect.top) / size.cellH)
  if (x < 0 || x >= GRID_W || y < 0 || y >= GRID_H) return null
  return { x, y }
}

const isLeftHalf = (clientX: number) => {
  const el = fsHost.value
  const mid = el
    ? el.getBoundingClientRect().left + el.getBoundingClientRect().width / 2
    : window.innerWidth / 2
  return clientX < mid
}

const applyPenFloat = () => {
  const x = Math.max(0, Math.min(GRID_W - 1, Math.round(state.penFX)))
  const y = Math.max(0, Math.min(GRID_H - 1, Math.round(state.penFY)))
  state.penX = x
  state.penY = y
}

const stampPen = () => {
  changePixel(state.penX, state.penY, true)
}

let lastPenKey = ''

const syncPenAndStroke = () => {
  const key = `${state.penX},${state.penY}`
  if (key !== lastPenKey) {
    lastPenKey = key
    if (state.penDown) stampPen()
  }
}

const paintBrushAtPoint = (clientX: number, clientY: number) => {
  const cell = cellFromPoint(clientX, clientY)
  if (!cell) return
  state.penX = cell.x
  state.penY = cell.y
  state.penFX = cell.x
  state.penFY = cell.y
  // touch fallback still uses 3×3 so fingers can hit tiny cells
  const half = Math.floor(BRUSH / 2)
  const centerVal = state.matrix[cell.y]?.[cell.x]
  if (centerVal === undefined) return
  for (let dy = -half; dy <= half; dy++) {
    for (let dx = -half; dx <= half; dx++) {
      const px = cell.x + dx
      const py = cell.y + dy
      if (px < 0 || px >= GRID_W || py < 0 || py >= GRID_H) continue
      if (!state.matrix[py] || state.matrix[py][px] === undefined) continue
      if (dx === 0 && dy === 0) {
        changePixel(px, py, true)
      } else if (state.matrix[py][px] === centerVal) {
        changePixel(px, py, true)
      }
    }
  }
}

let lastPaintKey = ''

const paintCellTraditional = (clientX: number, clientY: number) => {
  const cell = cellFromPoint(clientX, clientY)
  if (!cell) return
  const key = `${cell.x},${cell.y}`
  if (key === lastPaintKey) return
  lastPaintKey = key
  changePixel(cell.x, cell.y, true)
}

const onPointerDown = (e: PointerEvent) => {
  if (e.button != null && e.button !== 0 && e.pointerType === 'mouse') return
  const target = e.currentTarget as HTMLElement | null
  try {
    target?.setPointerCapture?.(e.pointerId)
  } catch {}

  // Touch outside fullscreen: open fullscreen first.
  // Keep inlinePaint as paint fallback after a failed attempt; hint/button can retry FS.
  if (!state.isFs && isTouchLike() && !state.inlinePaint) {
    void enterFullscreen()
    return
  }

  if (state.isFs) {
    if (isLeftHalf(e.clientX)) {
      // Left half: 落笔 — stamp once; hold for continuous stroke while moving pen
      state.leftPid = e.pointerId
      state.penDown = true
      lastPenKey = `${state.penX},${state.penY}`
      stampPen()
    } else {
      // Right half: trackpad — relative pen move, never jump to finger
      state.rightPid = e.pointerId
      state.penActive = true
      state.trackX = e.clientX
      state.trackY = e.clientY
    }
    return
  }

  // Inline paint (desktop, or touch after FS fallback).
  lastPaintKey = ''
  state.mousedown = true
  if (isTouchLike() && state.inlinePaint) {
    paintBrushAtPoint(e.clientX, e.clientY)
  } else {
    paintCellTraditional(e.clientX, e.clientY)
  }
}

const onPointerMove = (e: PointerEvent) => {
  if (state.isFs) {
    if (e.pointerId === state.rightPid) {
      const size = cellSize()
      if (!size) return
      const dx = e.clientX - state.trackX
      const dy = e.clientY - state.trackY
      state.trackX = e.clientX
      state.trackY = e.clientY
      state.penFX += (dx / size.cellW) * PEN_GAIN
      state.penFY += (dy / size.cellH) * PEN_GAIN
      applyPenFloat()
      syncPenAndStroke()
    }
    return
  }

  if (state.mousedown) {
    if (isTouchLike() && state.inlinePaint) {
      paintBrushAtPoint(e.clientX, e.clientY)
    } else {
      paintCellTraditional(e.clientX, e.clientY)
    }
  }
}

const clearPointer = (pointerId: number) => {
  if (pointerId === state.leftPid) {
    state.leftPid = null
    state.penDown = false
  }
  if (pointerId === state.rightPid) {
    state.rightPid = null
    state.penActive = false
  }
}

const onPointerUp = (e: PointerEvent) => {
  if (state.isFs) {
    clearPointer(e.pointerId)
    return
  }
  state.mousedown = false
}

const onPointerCancel = (e: PointerEvent) => {
  if (state.isFs) {
    clearPointer(e.pointerId)
    return
  }
  state.mousedown = false
}

const onWindowPointerUp = (e: PointerEvent) => {
  if (state.isFs) {
    clearPointer(e.pointerId)
    return
  }
  state.mousedown = false
}

onMounted(async ()=>{
  window.addEventListener('pointerup', onWindowPointerUp)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  document.addEventListener('webkitfullscreenchange', onFullscreenChange as EventListener)
  window.addEventListener('k5app:exit-fs', onK5ExitFsRequest as EventListener)
  if(route.query.url){
    const img = await fetch(route.query.url, {
      responseType: 'blob'
    });
    useImg(window.URL.createObjectURL(await img.blob()))
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('pointerup', onWindowPointerUp)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  document.removeEventListener('webkitfullscreenchange', onFullscreenChange as EventListener)
  window.removeEventListener('k5app:exit-fs', onK5ExitFsRequest as EventListener)
  if (isDocumentFullscreen() || state.isFs) {
    void exitFullscreen()
  } else {
    unlockOrientation()
  }
})

const negativeIt = () => {
  const matrix = state.matrix
  matrix.map((y: any, yi: any)=>{
    y.map((x: any, xi: any)=>{
      matrix[yi][xi] = x == '#fff' ? '#000' : '#fff'
    })
  })
  state.matrix = matrix
}

const changePixel = (x: int, y: int, force?: boolean) => {
  if(state.mousedown || force){
    if (!state.matrix[y] || state.matrix[y][x] === undefined) return
    const matrix = state.matrix
    matrix[y][x] = state.matrix[y][x] == '#fff' ? '#000' : '#fff'
    state.matrix = matrix
  }
}

const useImg = (url: string) => {
    const canvas = document.createElement("canvas");
    canvas.width = 128;
    canvas.height = 64;
    const canvas2 = canvas.cloneNode();
    const canvasDiv = document.getElementById('canvasDiv');
    canvasDiv.innerHTML = "";
    canvasDiv?.append(canvas, canvas2);
    const img = new Image()
    img.src = url;
    img.onload = () => {
      const ctx = canvas.getContext('2d');
      ctx?.drawImage(img, 0, 0, 128, 64);
      const imageData = ctx?.getImageData(0, 0, canvas.width, canvas.height).data;
      state.cache = imageData;
      function getPixel(x: any, y: any) {
          const index = y * 128 + x;
          const i = index * 4;
          return imageData[i] + imageData[i + 1] + imageData[i + 2] > state.threshold * 3 ? 0 : 1;
      }

      const matrix = [];

      for (let y = 0; y < 64; y++) {
          matrix.push([])
          matrix[y] = []
          for (let x = 0; x < 128; x++) {
              const pixel = !getPixel(x, y);
              matrix[y][x] = pixel ? '#fff' : '#000';
          }
      }

      state.matrix = matrix
    }
}

const selectFile = () => {
  const input = document.createElement('input');
  input.type = 'file';
  input.onchange = async () => {
    const blob = new Blob([input.files[0]], { type: 'application/octet-stream' });
    const file = URL.createObjectURL(blob);
    const canvas = document.createElement("canvas");
    canvas.width = 128;
    canvas.height = 64;
    
    const img = new Image()
    img.src = file;
    img.onload = () => {
      const ctx = canvas.getContext('2d');
      ctx?.drawImage(img, 0, 0, 128, 64);
      const imageData = ctx?.getImageData(0, 0, canvas.width, canvas.height).data;
      state.cache = imageData;
      function getPixel(x: any, y: any) {
          const index = y * 128 + x;
          const i = index * 4;
          return imageData[i] + imageData[i + 1] + imageData[i + 2] > state.threshold * 3 ? 0 : 1;
      }
      
      const matrix = [];

      for (let y = 0; y < 64; y++) {
          matrix.push([])
          matrix[y] = []
          for (let x = 0; x < 128; x++) {
              const pixel = !getPixel(x, y);
              matrix[y][x] = pixel ? '#fff' : '#000';
          }
      }

      state.matrix = matrix
    }
  };
  input.click();
}

const saveIt = async () => {
  const matrix = state.matrix
  const canvas = document.createElement("canvas");
  canvas.width = 128;
  canvas.height = 64;
  const ctx = canvas.getContext('2d');
  if(ctx){
    ctx.fillStyle = "#fff";
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = "#000";
  }
  for (let y = 0; y < 64; y++) {
    for (let x = 0; x < 128; x++) {
      if(matrix[y][x] == '#000'){
        ctx?.beginPath();
        ctx?.rect(x, y, 1, 1);
        ctx?.fill();
      }
    }
  }
  // 统一保存入口：先让用户指定目标目录，取消则不保存、不回退默认目录。
  // 原实现用 data: URL 直接触发下载，在安卓 WebView 里会静默失败。
  await saveDataUrlAs(canvas.toDataURL('image/jpeg', 1.0), 'image.jpg');
}

const flashIt = async () => {
    const outputArray = new Uint8Array(1024);
    // getPixel(i) outputs the pixel value for any x y coordinate. 0 = black, 1 = white.
    // the outputArray is 1024 bytes, where each byte is 8 pixels IN VERTICAL ORDER.

    let i = 0;
    for (let y = 0; y < 64; y += 8) {
        for (let x = 0; x < 128; x++) {
            let byte = 0;
            for (let i = 0; i < 8; i++) {
              byte |= (state.matrix[y + i][x] == '#000' ? 1 : 0 ) << i;
            }
            outputArray[i++] = byte;
        }
    }

    state.binaryFile = outputArray;
  if(appStore.connectState != true){alert(sessionStorage.getItem('noticeConnectK5')); return;};
  if(appStore.configuration?.uart == "official"){
    if(appStore.configuration?.charset != "gb2312"){
      alert(sessionStorage.getItem('noticeVersionNoSupport'));
      return;
    }
    state.loading = true
    let position = 0x3000;
    await eeprom_init(appStore.connectPort);
    let rawEEPROM = state.binaryFile;
    rawEEPROM = [0x5A, 0x5A, 0xBC, 0x9A, 0x00, 0x04, 0xFF, 0xFF, ...rawEEPROM];
    for (let i = position; i < rawEEPROM.length + position; i += 0x40) {
      await eeprom_write(appStore.connectPort, i, rawEEPROM.slice(i - position, i - position + 0x40), rawEEPROM.slice(i - position, i - position + 0x40).length, appStore.configuration?.uart);
    }
    await eeprom_reboot(appStore.connectPort);
    state.loading = false
    await disconnect(appStore.connectPort);
    appStore.updateSettings({ connectState: false, connectPort: null, firmwareVersion: "" });
    return;
  }
  if(appStore.configuration?.charset != "losehu" && appStore.configuration?.charset != "gb2312"){
    alert(sessionStorage.getItem('noticeVersionNoSupport'));
    return;
  }
  state.loading = true
  const isUveGb = appStore.firmwareVersion?.startsWith('UVE') && appStore.configuration?.charset == "gb2312";
  let position = 0x1E350;
  if(appStore.configuration?.charset == "gb2312")position = isUveGb ? 0x0080 : 0x2080;
  await eeprom_init(appStore.connectPort);
  const rawEEPROM = state.binaryFile;
  for (let i = position; i < rawEEPROM.length + position; i += 0x40) {
    const chunk = rawEEPROM.slice(i - position, i - position + 0x40);
    if(isUveGb){
      await shared_write(appStore.connectPort, i, chunk, chunk.length);
    }else{
      await eeprom_write(appStore.connectPort, i, chunk, chunk.length, appStore.configuration?.uart);
    }
  }
  await eeprom_reboot(appStore.connectPort);
  state.loading = false
}

// ---------------- 任务③：从设备读取当前开机图片 ----------------
// 地址与协议分支和 flashIt（写入开机图片）保持一致：
//   official + gb2312：EEPROM 0x3000 处有 [5A 5A BC 9A ...] 头，图片从 0x3008 开始；
//   losehu：EEPROM 0x1E350；gb2312（非 UVE）：EEPROM 0x2080；
//   gb2312 + UVE：shared 区 0x0080（shared_read）。
const IMG_SIZE = 1024;
const CHUNK = 0x40;

const readImageChunks = async (
  readFn: (addr: number, size: number) => Promise<Uint8Array>,
  position: number
) => {
  const out = new Uint8Array(IMG_SIZE);
  for (let i = 0; i < IMG_SIZE; i += CHUNK) {
    const data = await readFn(position + i, CHUNK);
    if (!data || data.length === 0) {
      throw new Error(`0x${(position + i).toString(16)} 处读取失败`);
    }
    out.set(data.subarray(0, Math.min(data.length, IMG_SIZE - i)), i);
  }
  return out;
};

const applyDeviceImage = (bytes: Uint8Array) => {
  // 与 flashIt 的纵向打包互逆：byte = data[(y>>3)*128 + x] 的第 (y&7) 位，1 为黑色。
  const matrix: any = [];
  for (let y = 0; y < 64; y++) {
    matrix.push([]);
    for (let x = 0; x < 128; x++) {
      const bit = (bytes[(y >> 3) * 128 + x] >> (y & 7)) & 1;
      matrix[y][x] = bit ? '#000' : '#fff';
    }
  }
  state.matrix = matrix;

  // 生成大图预览（白底黑像素）
  const canvas = document.createElement('canvas');
  canvas.width = 128;
  canvas.height = 64;
  const ctx = canvas.getContext('2d');
  if (ctx) {
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, 128, 64);
    ctx.fillStyle = '#000';
    for (let y = 0; y < 64; y++) {
      for (let x = 0; x < 128; x++) {
        if (matrix[y][x] === '#000') {
          ctx.fillRect(x, y, 1, 1);
        }
      }
    }
  }
  state.previewUrl = canvas.toDataURL('image/png');
  state.previewVisible = true;
};

const readFromDevice = async () => {
  if (appStore.connectState != true) {
    alert(sessionStorage.getItem('noticeConnectK5'));
    return;
  }
  const cfg = appStore.configuration;
  if (cfg?.uart == 'official' && cfg?.charset != 'gb2312') {
    alert(sessionStorage.getItem('noticeVersionNoSupport'));
    return;
  }
  if (cfg?.uart != 'official' && cfg?.charset != 'losehu' && cfg?.charset != 'gb2312') {
    alert(sessionStorage.getItem('noticeVersionNoSupport'));
    return;
  }
  state.reading = true;
  try {
    await eeprom_init(appStore.connectPort);
    let bytes: Uint8Array;
    if (cfg?.uart == 'official') {
      const header = await eeprom_read(appStore.connectPort, 0x3000, 0x08, 'official');
      if (
        !header ||
        header.length < 4 ||
        header[0] !== 0x5a ||
        header[1] !== 0x5a ||
        header[2] !== 0xbc ||
        header[3] !== 0x9a
      ) {
        throw new Error('EEPROM 0x3000 处未找到有效的开机图片标志，请先写入一次开机图片');
      }
      bytes = await readImageChunks(
        (addr, size) => eeprom_read(appStore.connectPort, addr, size, 'official'),
        0x3008
      );
    } else {
      const isUveGb = appStore.firmwareVersion?.startsWith('UVE') && cfg?.charset == 'gb2312';
      if (isUveGb) {
        bytes = await readImageChunks(
          (addr, size) => shared_read(appStore.connectPort, addr, size),
          0x0080
        );
      } else {
        const position = cfg?.charset == 'gb2312' ? 0x2080 : 0x1e350;
        bytes = await readImageChunks(
          (addr, size) => eeprom_read(appStore.connectPort, addr, size, cfg?.uart),
          position
        );
      }
    }
    applyDeviceImage(bytes);
    Message.success(t('image.readOk'));
  } catch (e: any) {
    Message.error(`${t('image.readFail')}：${e?.message || e}`);
  } finally {
    state.reading = false;
  }
};

const savePreviewAs = async () => {
  if (!state.previewUrl) return;
  // 统一保存入口：先让用户指定目标目录，取消则不保存、不回退默认目录。
  await saveDataUrlAs(state.previewUrl, 'boot-image.png');
};

const changeThreshold = () => {
      const imageData = state.cache;
      function getPixel(x: any, y: any) {
          const index = y * 128 + x;
          const i = index * 4;
          return imageData[i] + imageData[i + 1] + imageData[i + 2] > state.threshold * 3 ? 0 : 1;
      }
      
      const matrix = [];

      for (let y = 0; y < 64; y++) {
          matrix.push([])
          matrix[y] = []
          for (let x = 0; x < 128; x++) {
              const pixel = !getPixel(x, y);
              matrix[y][x] = pixel ? '#fff' : '#000';
          }
      }

      state.matrix = matrix
}
</script>

<script lang="ts">
  export default {
    name: 'Image',
  };
</script>

<style scoped lang="less">
  .container {
    padding: 0 20px 20px 20px;
    :deep(.arco-list-content) {
      overflow-x: hidden;
    }

    :deep(.arco-card-meta-title) {
      font-size: 14px;
    }
  }
  :deep(.arco-list-col) {
    display: flex;
    flex-direction: row;
    flex-wrap: wrap;
    justify-content: space-between;
  }


  :deep(.block-title) {
    margin: 0 0 12px 0;
    font-size: 14px;
  }
  :deep(.list-wrap) {
    // min-height: 140px;
    .list-row {
      align-items: stretch;
      .list-col {
        margin-bottom: 16px;
      }
    }
    :deep(.arco-space) {
      width: 100%;
      .arco-space-item {
        &:last-child {
          flex: 1;
        }
      }
    }
  }
</style>
