<template>
  <div class="container">
    <t-message v-if="ephState.fetching" theme="loading" class="midMessage">
      <span>正在联网获取星历</span>
      <a-link theme="primary" style="margin-left: 12px;" @click="cancelFetchEphemeris">终止获取</a-link>
    </t-message>
    <t-message v-if="locState.locating" theme="loading" class="midMessageLoc">
      <div style="display: flex; align-items: center; min-width: 220px; max-width: calc(100vw - 48px);">
        <span style="flex: 1;">{{ locState.stage }}中（{{ locState.elapsed }}秒）</span>
        <a-button size="mini" type="text" style="margin-left: 12px; flex-shrink: 0;" @click="cancelLocate">停止定位</a-button>
      </div>
    </t-message>
    <a-modal width="650px" class="self-sat-modal" v-model:visible="state.selfSatModal" @ok="addSelfSat">
      <template #title>
        {{ $t("sat.selfSatInfo") }}
      </template>
      <div>
        <a-textarea v-model="state.selfSatInfo" style="height: 120px;" :placeholder="selfSatPlaceholder" />
      </div>
    </a-modal>
    <a-modal v-model:visible="state.visible" @ok="handleOk" :ok-text="$t('tool.scaned')">
      <template #title>
        {{ $t('tool.scanqr') }}
      </template>
      <div style="text-align: center;">
        <img :src="state.qrcode" /><br>
        {{ $t('tool.scannotice') }}
      </div>
    </a-modal>
    <Breadcrumb :items="[$t('menu.list'), $t('menu.satellite')]" />
    <a-row :gutter="20" align="stretch">
      <a-col :span="24">
        <a-card class="general-card" :title="$t('menu.satellite') + $t('global.onStart')">
          <a-spin :loading="loading" style="width: 100%;" tip="正在处理 ...">
            <a-form-item :label-col-style="{ width: '25%' }" field="dt" :label="$t('tool.brtime')"
              @click="() => { state.showHide += 1 }">
              {{ state.dt }}
            </a-form-item>
            <a-form-item v-show="state.showHide >= 5" :label-col-style="{ width: '25%' }" field="dtCustom"
              label="自定义时间">
              <div>
                <a-date-picker class="field-md" show-time
                  :time-picker-props="{ defaultValue: '00:00:00' }" format="YYYY-MM-DD HH:mm:ss"
                  v-model="state.dtCustom" />
                &nbsp;&nbsp;<t-button size="small" theme="success" @click="writeTime">写入时间到台站</t-button>
              </div>
            </a-form-item>
            <a-form-item :label-col-style="{ width: '25%' }" field="sat" :label="$t('tool.selectSatellite')">
              <div style="width: 100%;">
                <a-select v-model="state.sat" @change="changeSat" :placeholder="$t('tool.selectSatellite') + '...'"
                  allow-search allow-clear>
                  <a-option v-for="item in state.satData" :key="item.name" :value="item.name">{{ item.name }}</a-option>
                </a-select>
                <a-link @click="() => { state.selfSatModal = true }" style="margin-top: 10px;">{{ $t("sat.addSelfSat")
                  }}</a-link>
                <a-button size="small" style="margin-left: 16px; margin-top: 10px;" @click="importLastEphemeris">导入上次星历</a-button>
              </div>
            </a-form-item>
            <a-form-item :label-col-style="{ width: '25%' }" field="lng" :label="$t('tool.longitude')">
              <a-input-number ref="lngRef" :precision="6" v-model="state.lng" />
            </a-form-item>
            <a-form-item :label-col-style="{ width: '25%' }" field="lat" :label="$t('tool.latitude')">
              <a-input-number ref="latRef" :precision="6" v-model="state.lat" />
            </a-form-item>
            <a-form-item :label-col-style="{ width: '25%' }" field="alt" :label="$t('tool.altitude')">
              <a-input-number ref="altRef" :precision="0" v-model="state.alt" />
            </a-form-item>
            <a-form-item :label-col-style="{ width: '25%' }" label="">
              <a-space>
                <a-button @click="getLocation">{{ $t('tool.brlonlat') }}</a-button>
                <a-button @click="scanLocation">{{ $t('tool.phonelonlat') }}</a-button>
                <a-button type="primary" @click="getPass">{{ $t('tool.satpasstime') }}</a-button>
              </a-space>
            </a-form-item>
            <a-form-item :label-col-style="{ width: '25%' }" field="pass" :label="$t('tool.selectPassTime')">
              <a-select v-model="state.pass" allow-search allow-clear>
                <a-option v-for="item in state.passOption" :key="item[0] + '|' + item[1]"
                  :value="item[0] + '|' + item[1]">{{ item[0] + " - " + item[1] }}</a-option>
              </a-select>
            </a-form-item>
            <a-form-item v-show="state.showHide >= 5" :label-col-style="{ width: '25%' }" field="passCustom"
              label="自定义过境时间">
              <a-range-picker class="field-lg" show-time
                :time-picker-props="{ defaultValue: ['00:00:00', '00:00:00'] }" format="YYYY-MM-DD HH:mm:ss"
                v-model="state.passCustom" />
            </a-form-item>
            <a-form-item :label-col-style="{ width: '25%' }" field="tx" :label="$t('tool.txFreq')">
              <a-input-number :precision="5" v-model="state.tx" />
            </a-form-item>
            <a-form-item :label-col-style="{ width: '25%' }" field="txTone" :label="$t('tool.txTone')">
              <a-select v-model="state.txTone" allow-search allow-clear>
                <a-option :value="0">{{ $t('tool.off') }}</a-option>
                <a-option v-for="item in state.CTCSSOption" :key="item" :value="item">{{ item.toFixed(1) }}</a-option>
              </a-select>
            </a-form-item>
            <a-form-item :label-col-style="{ width: '25%' }" field="rx" :label="$t('tool.rxFreq')">
              <a-input-number :precision="5" v-model="state.rx" />
            </a-form-item>
            <a-form-item :label-col-style="{ width: '25%' }" field="rxTone" :label="$t('tool.rxTone')">
              <a-select v-model="state.rxTone" allow-search allow-clear>
                <a-option :value="0">{{ $t('tool.off') }}</a-option>
                <a-option v-for="item in state.CTCSSOption" :key="item" :value="item">{{ item.toFixed(1) }}</a-option>
              </a-select>
            </a-form-item>
            <a-form-item :label-col-style="{ width: '25%' }" label="">
              <a-space>
                <a-button type="primary" @click="writeIt">{{ $t('tool.writeData') }}</a-button>
                <a-button v-if="!state.autoOpenLook4sat" @click="openLook4sat">打开look4sat</a-button>
                <span style="display: inline-flex; align-items: center;">
                  <a-switch v-model="state.autoOpenLook4sat" type="round" @change="saveAutoOpenLook4sat" />
                  <span style="margin-left: 8px; font-size: 12px;">写入后自动打开look4sat</span>
                </span>
              </a-space>
            </a-form-item>
            <a-divider />
            <div id="statusArea" class="status-area">{{ state.status }}</div>
          </a-spin>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script lang="ts" setup>
import { ref, reactive, nextTick, onMounted, onUnmounted } from 'vue';
import { Message } from '@arco-design/web-vue';
import { useAppStore } from '@/store';
import { eeprom_write, eeprom_reboot, eeprom_init, hexReverseStringToUint8Array, stringToUint8Array } from '@/utils/serial.js';
import useLoading from '@/hooks/loading';
import QRCode from 'qrcode';
import { getPasses, getDopplerShifts, parseGpJson, parseSelfSatInput, toSatrec } from '@/utils/satellite.js';
import { getCurrentPositionCompat } from '@/utils/location.js';

const { loading, setLoading } = useLoading(true);

const selfSatPlaceholder = `粘贴 TLE / OMM JSON，或星历文件 URL

URL 例：
https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=json

TLE 例：
ISS (ZARYA)
1 25544U 98067A   24320.36274227  .00015569  00000+0  28188-3 0  9999
2 25544  51.6413 286.4173 0007936 217.3657 298.3197 15.49809951481990

OMM JSON 例：
{"OBJECT_NAME":"ISS (ZARYA)","OBJECT_ID":"1998-067A","EPOCH":"2026-09-13T04:12:47.894976","MEAN_MOTION":15.49096932,"ECCENTRICITY":0.00049173,"INCLINATION":51.6307,"RA_OF_ASC_NODE":224.6171,"ARG_OF_PERICENTER":134.673,"MEAN_ANOMALY":225.4659,"EPHEMERIS_TYPE":0,"CLASSIFICATION_TYPE":"U","NORAD_CAT_ID":25544,"ELEMENT_SET_NO":999,"REV_AT_EPOCH":58539,"BSTAR":9.6694874e-5,"MEAN_MOTION_DOT":4.898e-5,"MEAN_MOTION_DDOT":0}`

const appStore = useAppStore();

const lngRef: any = ref(null)
const latRef: any = ref(null)
const altRef: any = ref(null)

const state: {
  uuid: string,
  qrcode: string,
  showHide: number,
  status: string,
  warnings: string[],
  sat: string,
  satData: any[],
  lng: number,
  lat: number,
  alt: number,
  tx: number,
  rx: number,
  txTone: number | undefined,
  CTCSSOption: any[],
  pass: any,
  passOption: any[],
  rxTone: number | undefined,
  dt: any,
  timer: any,
  passCustom: any,
  dtCustom: any,
  freqDb: any,
  visible: boolean,
  selfSatModal: boolean,
  selfSatInfo: string,
  autoOpenLook4sat: boolean,
} = reactive({
  uuid: '',
  qrcode: '',
  visible: false,
  showHide: 0,
  status: "点击写入按钮写入卫星数据到设备\n\n",
  warnings: [],
  sat: '',
  satData: [],
  lng: 0,
  lat: 0,
  alt: 0,
  tx: 0,
  rx: 0,
  txTone: 0,
  rxTone: 0,
  CTCSSOption: [67.0, 69.3, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4,
    88.5, 91.5, 94.8, 97.4, 100.0, 103.5, 107.2, 110.9,
    114.8, 118.8, 123.0, 127.3, 131.8, 136.5, 141.3, 146.2,
    151.4, 156.7, 159.8, 162.2, 165.5, 167.9, 171.3, 173.8,
    177.3, 179.9, 183.5, 186.2, 189.9, 192.8, 196.6, 199.5,
    203.5, 206.5, 210.7, 218.1, 225.7, 229.1, 233.6, 241.8,
    250.3, 254.1],
  pass: undefined,
  passOption: [],
  dt: '',
  timer: undefined,
  passCustom: undefined,
  dtCustom: undefined,
  selfSatModal: false,
  selfSatInfo: '',
  autoOpenLook4sat: false,
})

// 写入后自动打开 look4sat 开关：持久化到 localStorage
state.autoOpenLook4sat = localStorage.getItem('satAutoOpenLook4sat') === '1'
const saveAutoOpenLook4sat = (val: any) => {
  try { localStorage.setItem('satAutoOpenLook4sat', val ? '1' : '0') } catch { }
}

// 打开 look4sat（原生桥 K5APP.openApp；桌面端提示不支持）
const LOOK4SAT_PKG = 'cn.ba7opf.look4sat'
const openLook4sat = async () => {
  const k5app = (typeof window !== 'undefined') ? (window as any).K5APP : null
  if (k5app && typeof k5app.openApp === 'function') {
    try {
      await k5app.openApp(LOOK4SAT_PKG)
    } catch (err: any) {
      Message.error((err && err.message) ? err.message : '未安装 look4sat，无法打开')
    }
  } else {
    Message.warning('当前环境不支持直接打开安卓应用（仅安卓端可用）')
  }
}

onMounted(async () => {
  try {
    if (sessionStorage.getItem('satFrequenciesRst')) {
      state.freqDb = JSON.parse(sessionStorage.getItem('satFrequenciesRst') || "[]")
    } else {
      const rst = await (await fetch('https://github.jobcher.com/gh/https://raw.githubusercontent.com/palewire/ham-satellite-database/main/data/amsat-active-frequencies.json')).text()
      state.freqDb = JSON.parse(rst)
      sessionStorage.setItem("satFrequenciesRst", rst)
    }
  }
  catch { }

  // 定位已完成时跳过 localStorage 恢复，避免覆盖定位结果（竞态保护）
  if (!locFilled) {
    state.lng = parseFloat(localStorage.getItem('myLng') || '0')
    state.lat = parseFloat(localStorage.getItem('myLat') || '0')
    state.alt = parseFloat(localStorage.getItem('myAlt') || '0')

    await lngRef.value.$forceUpdate()
    await latRef.value.$forceUpdate()
    await altRef.value.$forceUpdate()
    state.lng = parseFloat(lngRef.value.inputRef.modelValue || '0')
    state.lat = parseFloat(latRef.value.inputRef.modelValue || '0')
    state.alt = parseFloat(altRef.value.inputRef.modelValue || '0')
  }

  state.timer = setInterval(() => {
    state.dt = new Date().toLocaleString()
    localStorage.setItem('myLng', state.lng.toString());
    localStorage.setItem('myLat', state.lat.toString());
    localStorage.setItem('myAlt', state.alt.toString());
  }, 1000)
})

onUnmounted(() => {
  try {
    clearInterval(state.timer)
  } catch { }
  try {
    if (locTimer) clearInterval(locTimer)
  } catch { }
})

const writeTime = async () => {
  if (appStore.connectState != true) { alert(sessionStorage.getItem('noticeConnectK5')); return; };
  setLoading(true)
  await eeprom_init(appStore.connectPort);
  await syncTime();
  await eeprom_reboot(appStore.connectPort);
  setLoading(false)
}

const syncTime = async () => {
  const date = state.dtCustom ? new Date(state.dtCustom) : new Date();
  const dateArray = [
    ...hexReverseStringToUint8Array(parseInt(date.getFullYear().toString().substring(2, 4)).toString(16)),
    ...hexReverseStringToUint8Array((date.getMonth() + 1).toString(16)),
    ...hexReverseStringToUint8Array(date.getDate().toString(16)),
    ...hexReverseStringToUint8Array(date.getHours().toString(16)),
    ...hexReverseStringToUint8Array(date.getMinutes().toString(16)),
    ...hexReverseStringToUint8Array(date.getSeconds().toString(16))
  ]
  await eeprom_write(appStore.connectPort, 0x02BC0, new Uint8Array(dateArray), 0x06, appStore.configuration?.uart);
}

const changeSat = async (sat: any) => {
  const data = state.satData.find(e => e.name == sat);
  if (data && (data.omm || data.tle1)) {
    state.status += '\n卫星参数：\n'
    if (data.omm) {
      state.status += `${data.omm.OBJECT_NAME} | NORAD ${data.omm.NORAD_CAT_ID}\n`
      state.status += `历元 ${data.omm.EPOCH}\n`
      state.status += `倾角 ${data.omm.INCLINATION}° | 偏心率 ${data.omm.ECCENTRICITY}\n`
      state.status += `平均运动 ${data.omm.MEAN_MOTION} rev/day\n`
    } else if (data.tle1) {
      state.status += data.tle1 + '\n'
      state.status += data.tle2 + '\n'
    }
    let freqFlag = false
    const noradId = data.omm
      ? String(data.omm.NORAD_CAT_ID)
      : (data.tle2?.split(' ')[1] || '').trim()
    state.freqDb.map((e: any) => {
      if (noradId && noradId == e.norad_id && e.mode.indexOf('FM') != -1) {
        console.log(e)
        freqFlag = true
        state.tx = e.uplink ? parseFloat(e.uplink.split('/')[0]) : 0
        state.rx = e.downlink ? parseFloat(e.downlink.split('/')[0]) : 0
        state.txTone = parseFloat([0, ...state.CTCSSOption].reduce((_e: any, _n: any) => {
          return e.mode.indexOf(_n) != -1 ? _n : _e
        }))
      }
    })
    if (!freqFlag) {
      state.tx = 0
      state.rx = 0
      state.txTone = 0
      state.rxTone = 0
    }
  }
  nextTick(() => {
    const textarea = document?.getElementById('statusArea');
    if (textarea) textarea.scrollTop = textarea?.scrollHeight;
  })
}

// 星历联网获取提示窗状态与中止控制器
const ephState = reactive({ fetching: false });
let ephAbort: AbortController | null = null;
const cancelFetchEphemeris = () => {
  if (ephAbort) {
    const ctrl = ephAbort;
    ephAbort = null;
    ctrl.abort();
    // 立即关闭提示窗并解除"正在处理"遮罩，不等 fetch 响应 abort
    ephState.fetching = false;
    setLoading(false);
  }
}

// 获取定位提示窗状态：阶段（网络定位/卫星定位）+ 已用时秒数，支持中断
const locState = reactive({ locating: false, stage: '网络定位', elapsed: 0 });
let locTimer: any = null;
let locCancelled = false;
// 定位是否已成功填入经纬度：用于挂载恢复 localStorage 时避免覆盖定位结果
let locFilled = false;
// 当前定位任务的取消控制器：cancel() 会中止原生卫星定位并丢弃后续结果
let locateCtl: { cancel: () => void } | null = null;
const startLocTip = () => {
  locCancelled = false;
  locState.locating = true;
  locState.stage = '网络定位';
  locState.elapsed = 0;
  if (locTimer) clearInterval(locTimer);
  locTimer = setInterval(() => { locState.elapsed += 1; }, 1000);
}
const stopLocTip = () => {
  locState.locating = false;
  if (locTimer) {
    clearInterval(locTimer);
    locTimer = null;
  }
}
const cancelLocate = () => {
  locCancelled = true;
  stopLocTip();
  // 真正停止任务：中止原生卫星定位监听，丢弃迟到的定位结果
  try { locateCtl && locateCtl.cancel(); } catch { }
  locateCtl = null;
  // 立即解除"正在处理"遮罩，不等底层定位超时
  setLoading(false);
  Message.info('已停止定位')
}

const initSat = async () => {
  setLoading(true)
  try {
    let rst = ''
    // 旧键 satRst 是 TLE；FORMAT=json 的 OMM 数组可直接给 json2satrec
    const cached = sessionStorage.getItem('satGpJson')
    if (cached) {
      rst = cached
    } else {
      // 联网获取前检测网络：无网络则提示且不发起请求
      if (!navigator.onLine) {
        Message.error('无网络无法获取星历')
        return
      }
      ephState.fetching = true
      const ctrl = new AbortController()
      ephAbort = ctrl
      try {
        rst = await (await fetch('https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=json', { signal: ctrl.signal })).text()
        // 取消后才返回的响应：丢弃，不进入解析
        if (ctrl.signal.aborted) {
          Message.info('已终止获取星历')
          return
        }
      } catch (err: any) {
        // 用户点击"终止获取"：关闭提示窗并恢复状态，不再走后续解析
        if (err && (err.name === 'AbortError' || ephAbort === null)) {
          Message.info('已终止获取星历')
          return
        }
        throw err
      } finally {
        ephState.fetching = false
        ephAbort = null
      }
    }
    const ommList = parseGpJson(rst)
    // 仅在解析出有效数据时写入缓存，避免把限流/错误页缓存后整段会话无法恢复
    if (!cached && ommList.length) {
      sessionStorage.setItem('satGpJson', rst)
      // 星历通过网络获取成功时提示（缓存加载不提示）
      Message.success('星历获取成功')
    }
    // 星历获取成功：自动把最新星历保存到应用内部存储（localStorage，关闭应用不清除），并记录保存时间
    if (ommList.length) {
      try {
        localStorage.setItem('k5webLastEphemeris', JSON.stringify({ data: rst, savedAt: Date.now() }))
      } catch { /* 存储写入失败不影响正常流程 */ }
    }
    state.satData = ommList.map((omm: any) => ({
      name: omm.OBJECT_NAME,
      omm,
    }))
  } finally {
    setLoading(false)
  }
}
initSat()

// 导入上次保存的星历（应用内部存储）
const importLastEphemeris = () => {
  try {
    const raw = localStorage.getItem('k5webLastEphemeris')
    if (!raw) {
      Message.warning('暂未保存过星历，请先成功获取星历')
      return
    }
    const obj = JSON.parse(raw)
    const ommList = parseGpJson(obj.data || '')
    if (!ommList.length) {
      Message.warning('已保存的星历数据无效，请先重新获取星历')
      return
    }
    state.satData = ommList.map((omm: any) => ({
      name: omm.OBJECT_NAME,
      omm,
    }))
    const p = (n: number) => String(n).padStart(2, '0')
    const d = new Date(obj.savedAt)
    const ts = `${d.getFullYear()}.${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
    Message.success(`已成功导入${ts}保存的星历`)
  } catch {
    Message.error('导入上次星历失败')
  }
}

const getLocation = async () => {
  setLoading(true)
  startLocTip()
  // 网络正常时沿用原有浏览器定位；无网络/网络定位失败时自动切换卫星定位兜底
  // 第二参数为阶段回调：'web' 网络定位开始 / 'native' 卫星定位兜底开始；
  // 返回的 promise 带 cancel()，供"停止定位"真正中止任务
  const p = getCurrentPositionCompat({ enableHighAccuracy: true, timeout: 15000 }, (stage) => {
    locState.stage = stage === 'native' ? '卫星定位' : '网络定位'
  })
  locateCtl = (p as any).cancel ? (p as any) : null
  p.then((e) => {
    stopLocTip()
    if (locCancelled) { setLoading(false); return }
    Message.success('定位成功')
    locFilled = true
    if (e.coords) {
      state.lat = e.coords.latitude
      state.lng = e.coords.longitude
      if (e.coords.altitude) state.alt = e.coords.altitude
    }
    setLoading(false)
  }).catch((err) => {
    stopLocTip()
    if (locCancelled) { setLoading(false); return }
    setLoading(false)
    if (err && err.name === 'LocationServiceDisabled') {
      // 定位服务未开启：原生侧已弹出"去开启"引导窗，这里同步提示
      Message.warning('设备定位服务未开启，请在弹窗中开启定位服务后重试')
    } else if (err && err.code === err.PERMISSION_DENIED) {
      Message.error('定位失败：请在系统设置中允许本应用使用位置权限')
    } else {
      Message.error('定位失败：' + (err && err.message ? err.message : '请检查设备定位服务是否开启'))
    }
  })
}
getLocation()

const scanLocation = async () => {
  state.visible = true
  state.uuid = crypto.randomUUID()
  let origin = location.origin
  if (origin == 'file://') {
    origin = 'https://k5.vicicode.cn'
  }
  state.qrcode = await QRCode.toDataURL(origin + '/#/satloc?uuid=' + state.uuid, { width: 250 })
  console.log(state.uuid)
}

const handleOk = async () => {
  const lol = await (await fetch('https://k5.vicicode.cn/api/lol', {
    method: "POST",
    mode: "cors",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      func: 1,
      uuid: state.uuid
    })
  })).json();
  const jsonLol = JSON.parse(lol.cache);
  if (jsonLol.length >= 3) {
    state.lng = jsonLol[0],
      state.lat = jsonLol[1],
      state.alt = jsonLol[2]
  }
}

const restoreRange = async (start: any = 0, uint8Array: any) => {
  await eeprom_init(appStore.connectPort);
  for (let i = start; i < uint8Array.length + start; i += 0x40) {
    await eeprom_write(appStore.connectPort, i, uint8Array.slice(i - start, i - start + 0x40), 0x40, appStore.configuration?.uart);
    state.status = state.status + "写入进度：" + (((i - start) / uint8Array.length) * 100).toFixed(1) + "%\n";
    nextTick(() => {
      const textarea = document?.getElementById('statusArea');
      if (textarea) textarea.scrollTop = textarea?.scrollHeight;
    })
  }
  state.status = state.status + "写入进度：100.0%\n";
}

const getPass = async () => {
  if (!state.sat) { alert('请选择卫星！'); return; };
  setLoading(true)
  // sv-SE（瑞典语）恰好使用：YYYY-MM-DD HH:mm:ss 格式。
  const timeFormatter = new Intl.DateTimeFormat('sv-SE', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  });

  const satItem = state.satData.find(e => e.name == state.sat)
  const satrec = toSatrec(satItem)
  if (!satrec) { alert('卫星星历无效！'); setLoading(false); return; }

  const res = getPasses(
    {
      satrec,
      latitude: state.lat,
      longitude: state.lng,
      heightKm: state.alt / 1000
    }
  )

  const passOption = []
  res.map((e: any) => {
    const _passOption = [timeFormatter.format(e.aos), timeFormatter.format(e.los)];
    passOption.push(_passOption);
  })
  if (passOption.length > 0) {
    state.pass = passOption[0][0] + "|" + passOption[0][1]
  } else {
    state.pass = undefined
  }
  state.passOption = passOption
  setLoading(false)
}

const writeIt = async () => {
  if (appStore.connectState != true) { alert(sessionStorage.getItem('noticeConnectK5')); return; };
  if(appStore.configuration?.sat != true){
    alert(sessionStorage.getItem('noticeVersionNoSupport'));
    return;
  }
  if (!state.sat) { alert('请选择卫星！'); return; };
  if(state.passCustom){
    state.pass = state.passCustom[0] + "|" + state.passCustom[1]
  }
  if (!state.pass) { alert('请选择过境时间！'); return; };
  setLoading(true)

  const satItem = state.satData.find(e => e.name == state.sat)
  const satrec = toSatrec(satItem)
  if (!satrec) { alert('卫星星历无效！'); setLoading(false); return; }

  const res = {
    shift_array: getDopplerShifts({
      satrec,
      latitude: state.lat,
      longitude: state.lng,
      heightKm: state.alt / 1000,
      downlinkHz: state.rx,
      uplinkHz: state.tx,
      startTime: new Date(state.pass.split('|')[0].replace(' ', 'T')),
      endTime: new Date(state.pass.split('|')[1].replace(' ', 'T')),
    })
  }

  const sat = state.sat
  const pass = state.pass.split('|')[0]
  const pass_year = pass.split('-')[0].substring(2, 4)
  const pass_month = pass.split('-')[1]
  const pass_day = pass.split('-')[2].split(' ')[0]
  const pass_hour = pass.split(' ')[1].split(':')[0]
  const pass_min = pass.split(' ')[1].split(':')[1]
  const pass_sec = pass.split(' ')[1].split(':')[2]
  const departure = state.pass.split('|')[1]
  const departure_year = departure.split('-')[0].substring(2, 4)
  const departure_month = departure.split('-')[1]
  const departure_day = departure.split('-')[2].split(' ')[0]
  const departure_hour = departure.split(' ')[1].split(':')[0]
  const departure_min = departure.split(' ')[1].split(':')[1]
  const departure_sec = departure.split(' ')[1].split(':')[2]
  // console.log(sat.trim(), new Date(pass), pass_year, pass_month, pass_day, pass_hour, pass_min, pass_sec)
  // console.log(sat.trim(), new Date(departure), departure_year, departure_month, departure_day, departure_hour, departure_min, departure_sec)

  await eeprom_init(appStore.connectPort);
  // 卫星名称
  let payload = new Uint8Array(10)
  payload.set(stringToUint8Array(sat.trim()).subarray(0, 9))
  await eeprom_write(appStore.connectPort, 0x2BA0, payload, 10, appStore.configuration?.uart);
  // 写入过境始末
  await eeprom_write(appStore.connectPort, 0x2BAA, hexReverseStringToUint8Array(parseInt(pass_year).toString(16)).subarray(0, 1), 0x01, appStore.configuration?.uart);
  await eeprom_write(appStore.connectPort, 0x2BAB, hexReverseStringToUint8Array(parseInt(pass_month).toString(16)).subarray(0, 1), 0x01, appStore.configuration?.uart);
  await eeprom_write(appStore.connectPort, 0x2BAC, hexReverseStringToUint8Array(parseInt(pass_day).toString(16)).subarray(0, 1), 0x01, appStore.configuration?.uart);
  await eeprom_write(appStore.connectPort, 0x2BAD, hexReverseStringToUint8Array(parseInt(pass_hour).toString(16)).subarray(0, 1), 0x01, appStore.configuration?.uart);
  await eeprom_write(appStore.connectPort, 0x2BAE, hexReverseStringToUint8Array(parseInt(pass_min).toString(16)).subarray(0, 1), 0x01, appStore.configuration?.uart);
  await eeprom_write(appStore.connectPort, 0x2BAF, hexReverseStringToUint8Array(parseInt(pass_sec).toString(16)).subarray(0, 1), 0x01, appStore.configuration?.uart);
  await eeprom_write(appStore.connectPort, 0x2BB0, hexReverseStringToUint8Array(parseInt(departure_year).toString(16)).subarray(0, 1), 0x01, appStore.configuration?.uart);
  await eeprom_write(appStore.connectPort, 0x2BB1, hexReverseStringToUint8Array(parseInt(departure_month).toString(16)).subarray(0, 1), 0x01, appStore.configuration?.uart);
  await eeprom_write(appStore.connectPort, 0x2BB2, hexReverseStringToUint8Array(parseInt(departure_day).toString(16)).subarray(0, 1), 0x01, appStore.configuration?.uart);
  await eeprom_write(appStore.connectPort, 0x2BB3, hexReverseStringToUint8Array(parseInt(departure_hour).toString(16)).subarray(0, 1), 0x01, appStore.configuration?.uart);
  await eeprom_write(appStore.connectPort, 0x2BB4, hexReverseStringToUint8Array(parseInt(departure_min).toString(16)).subarray(0, 1), 0x01, appStore.configuration?.uart);
  await eeprom_write(appStore.connectPort, 0x2BB5, hexReverseStringToUint8Array(parseInt(departure_sec).toString(16)).subarray(0, 1), 0x01, appStore.configuration?.uart);
  // 总过境时间
  payload = new Uint8Array(2)
  payload.set(hexReverseStringToUint8Array(((Date.parse(departure) - Date.parse(pass)) / 1000).toString(16)).subarray(0, 0x02))
  await eeprom_write(appStore.connectPort, 0x2BB6, payload, 0x02, appStore.configuration?.uart);
  // 手台的发射接收亚音
  payload = new Uint8Array(2)
  if (state.txTone && state.txTone > 0) {
    payload.set(hexReverseStringToUint8Array(parseInt((state.txTone * 10).toFixed(0)).toString(16)).subarray(0, 0x02))
  }
  await eeprom_write(appStore.connectPort, 0x2BB8, payload, 0x02, appStore.configuration?.uart);
  payload = new Uint8Array(2)
  if (state.rxTone && state.rxTone > 0) {
    payload.set(hexReverseStringToUint8Array(parseInt((state.rxTone * 10).toFixed(0)).toString(16)).subarray(0, 0x02))
  }
  await eeprom_write(appStore.connectPort, 0x2BBA, payload, 0x02, appStore.configuration?.uart);
  // 开始过境时间的UNIX时间戳与2000年1月1日UNIX时间戳的差
  payload = new Uint8Array(4)
  payload.set(hexReverseStringToUint8Array(((Date.parse(pass) - Date.parse('2000-01-01 00:00:00')) / 1000).toString(16)).subarray(0, 0x04))
  await eeprom_write(appStore.connectPort, 0x2BBC, payload, 0x04, appStore.configuration?.uart);

  const shift_arr: any = []
  res.shift_array.filter((num: any, index: any) => index % 2 === 0).map((e: any) => {
    const _tx = new Uint8Array(4)
    const _rx = new Uint8Array(4)
    _tx.set(hexReverseStringToUint8Array(parseInt(((state.tx * 1000000 + e[0]) / 10).toFixed(0)).toString(16)))
    _rx.set(hexReverseStringToUint8Array(parseInt(((state.rx * 1000000 + e[1]) / 10).toFixed(0)).toString(16)))
    shift_arr.push(..._tx, ..._rx)
  })
  payload = new Uint8Array(0x1E00)
  payload.set(new Uint8Array(shift_arr).subarray(0, 0x1E00))
  await restoreRange(0x1E200, payload)
  await syncTime()
  await eeprom_reboot(appStore.connectPort);
  // 写入成功：开关开启时自动跳转 look4sat
  if (state.autoOpenLook4sat) { openLook4sat() }
  setLoading(false)
}

const isValidURL = (url: string) => {
  const pattern = new RegExp('^(https?:\\/\\/)?' + // 协议 (http 或 https)
    '((([a-zA-Z\\d]([a-zA-Z\\d-]*[a-zA-Z\\d])*)\\.)+[a-zA-Z]{2,}|' + // 域名
    '((\\d{1,3}\\.){3}\\d{1,3}))' + // 或者 IP 地址 (IPv4)
    '(\\:\\d+)?(\\/[-a-zA-Z\\d%_.~+]*)*' + // 端口号和路径
    '(\\?[;&a-zA-Z\\d%_.~+=-]*)?' + // 查询字符串
    '(\\#[-a-zA-Z\\d_]*)?$', 'i'); // 锚点
  return !!pattern.test(url);
}

const addSelfSat = async () => {
  const input = (state.selfSatInfo || '').trim()
  if (!input) return
  let text = input
  if (isValidURL(input)) {
    try {
      text = await (await fetch(input)).text()
    } catch {
      alert('拉取 URL 失败（可能是跨域或网络问题），可手动打开链接后复制内容粘贴')
      return
    }
  }
  const sat = parseSelfSatInput(text)
  if (sat.length === 0) {
    alert('未识别到卫星数据，请粘贴/URL 指向 TLE 或 OMM JSON')
    return
  }
  state.satData = sat.concat(state.satData)
  state.selfSatInfo = ''
}
</script>

<script lang="ts">
export default {
  name: 'Sat',
};
</script>

<style scoped lang="less">
.midMessage {
  position: fixed;
  top: 20%;
  z-index: 99;
  left: 50%;
  transform: translateX(-50%);
  white-space: nowrap;
}

// 定位提示窗：与星历提示窗上下错开，避免两者同显时重叠遮盖
.midMessageLoc {
  position: fixed;
  top: calc(20% + 64px);
  z-index: 99;
  left: 50%;
  transform: translateX(-50%);
  white-space: nowrap;
}

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
