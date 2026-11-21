<template>
  <div class="container">
    <Breadcrumb :items="[$t('menu.list'), $t('menu.rb')]" />
    <a-row :gutter="20" align="stretch">
      <a-col :span="24">
        <a-card class="general-card">
          <template #title>
            <span @click="()=>{state.showHide += 1}">{{ $t('menu.rb') + $t('global.onStart') }}</span>
          </template>
        <a-tabs default-active-key="1">
          <a-tab-pane key="1" :title="$t('tool.quickbackup')">
            <a-space>
              <a-button type="primary" @click="backupConfig">{{ $t('tool.backupConfig') }}</a-button>
              <a-button @click="restoreConfig">{{ $t('tool.restoreConfig') }}</a-button>
              <a-button type="primary" @click="backupCalibration">{{ $t('tool.backupCalibration') }}</a-button>
              <a-button @click="restoreCalibration">{{ $t('tool.restoreCalibration') }}</a-button>
            </a-space>
          </a-tab-pane>
          <a-tab-pane key="2" :title="$t('tool.fullbackup')">
              <a-space>
                <a-button type="primary" @click="backup">{{ $t('tool.backup') }}</a-button>
                <a-input v-show="state.showHide >= 5" v-model="state.startInfo" />
                <a-button @click="restore">{{ $t('tool.restore') }}</a-button>
                <a-select v-model="state.eepromType" class="field-select" :placeholder="$t('tool.selectSize')">
                  <a-option value="1">{{ $t('global.8kb') }}</a-option>
                  <a-option value="5">{{ $t('global.64kb') }}</a-option>
                  <a-option value="2">{{ $t('global.128kb') }}</a-option>
                  <a-option value="3">{{ $t('global.256kb') }}</a-option>
                  <a-option value="4">{{ $t('global.512kb') }}</a-option>
                </a-select>
                <a-button type="text" @click="checkEeprom">{{ $t('tool.autocheck') }}</a-button>
              </a-space>
          </a-tab-pane>
          <a-tab-pane key="3" :title="$t('tool.cleardata')">
            <a-space>
              <a-button type="primary" @click="showClearDialog">{{ $t('tool.cleardata') }}</a-button>
            </a-space>
          </a-tab-pane>
        </a-tabs>
        <a-divider />
        <div id="statusArea" class="status-area" v-html="state.status"></div>
        </a-card>
      </a-col>
    </a-row>
    <t-dialog
      v-model:visible="state.showDialog"
      theme="warning"
      :header="state.dialogTitle >= 3 ? $t('tool.first') + state.dialogTitle + $t('tool.firstTitle') + $t('tool.last') : $t('tool.first') + state.dialogTitle + $t('tool.firstTitle')"
      :body="$t('tool.clearMessage')"
      @confirm="onClickConfirm"
    />
  </div>
</template>

<script lang="ts" setup>
import { reactive, nextTick } from 'vue';
import { useAppStore } from '@/store';
import { check_eeprom, eeprom_read, eeprom_write, eeprom_reboot, eeprom_init } from '@/utils/serial.js';
import { saveBlobAs } from '@/utils/download';
import { DialogPlugin } from 'tdesign-vue-next';

const appStore = useAppStore();

const state = reactive({
  status: "点击备份按钮将生成 EEPROM 备份文件<br/><br/>",
  eepromType: "",
  showHide: 0,
  startInfo: "0x00",
  showDialog: false,
  dialogTitle: 1
})

const onClickConfirm = () => {
  if(state.dialogTitle >= 3){
    state.showDialog = false;
    clearEEPROM();
    return;
  }
  state.dialogTitle += 1;
}

// 状态区截断上限：完整备份（512KB）要迭代 8192 次，逐次追加会让 HTML 字符串
// 无限增长，v-html 整段重解析越跑越慢——既拖慢页面也挤占主线程，放大串口
// 接收丢包概率。超过上限时丢弃最早的 25% 内容。
const STATUS_MAX_CHARS = 150_000;
const appendStatus = (line: string) => {
  let s = state.status + line + "<br/>";
  if (s.length > STATUS_MAX_CHARS) {
    s = s.slice(-Math.floor(STATUS_MAX_CHARS * 0.75));
  }
  state.status = s;
}
const scrollStatus = () => {
  nextTick(()=>{
    const textarea = document?.getElementById('statusArea');
    if(textarea)textarea.scrollTop = textarea?.scrollHeight;
  })
}

const showClearDialog = () => {
  state.dialogTitle = 1;
  state.showDialog = true;
}

const checkEeprom = async () => {
  if(appStore.connectState != true){alert(sessionStorage.getItem('noticeConnectK5')); return;};
  const eepromSize = await check_eeprom(appStore.connectPort, appStore.configuration?.uart);
  switch(eepromSize){
    case 0x2000:
      state.eepromType = "1";
      break;
    case 0x10000:
      state.eepromType = "5";
      break;
    case 0x20000:
      state.eepromType = "2";
      break;
    case 0x40000:
      state.eepromType = "3";
      break;
    case 0x80000:
      state.eepromType = "4";
      break;
    default:
      break;
  }
}

const clearEEPROM = async () => {
  if(appStore.connectState != true){alert(sessionStorage.getItem('noticeConnectK5')); return;};
  const eepromSize = await check_eeprom(appStore.connectPort, appStore.configuration?.uart);
  let rawEEPROM = new Uint8Array(0x40).fill(0xff);
  for (let i = 0; i < eepromSize; i += 0x40) {
    await eeprom_write(appStore.connectPort, i, rawEEPROM, 0x40, appStore.configuration?.uart);
    appendStatus("清空进度：" + (((i - 0) / eepromSize) * 100).toFixed(1) + "%");
    scrollStatus();
  }
  await eeprom_reboot(appStore.connectPort);
}

const backupRange = async (start: any, end: any, name: any = new Date() + '_backup.bin') =>{
  await eeprom_init(appStore.connectPort);
  let rawEEPROM = new Uint8Array(end - start);
  let lastPctInt = -1;
  for (let i = start; i < end; i += 0x40) {
    const data = await eeprom_read(appStore.connectPort, i, 0x40, appStore.configuration?.uart);
    rawEEPROM.set(data, i - start);
    // 进度只在整数百分比变化时写一次：大容量备份迭代上千次，逐次写
    // state.status 会让页面主线程越来越忙，加大串口接收丢包概率。
    const pct = ((i - start) / rawEEPROM.length) * 100;
    const pctInt = Math.floor(pct);
    if (pctInt !== lastPctInt) {
      lastPctInt = pctInt;
      appendStatus("备份进度：" + pct.toFixed(1) + "%");
      scrollStatus();
    }
  }
  appendStatus("备份进度：100.0%");
  scrollStatus();
  console.log(rawEEPROM);
  const blob = new Blob([rawEEPROM], { type: 'application/octet-stream' });
  // 统一保存入口：先让用户指定目标目录，取消则不保存、不回退默认目录。
  await saveBlobAs(blob, name);
}

const restoreRange = async (start: any = 0) => {
  await eeprom_init(appStore.connectPort);
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = '.bin';
  input.onchange = async() => {
    const blob = new Blob([input.files[0]], {type: 'application/octet-stream' });
    const rawEEPROM = new Uint8Array(await blob.arrayBuffer());
    for (let i = start; i < input.files[0].size + start; i += 0x40) {
      await eeprom_write(appStore.connectPort, i, rawEEPROM.slice(i - start, i - start + 0x40), rawEEPROM.slice(i - start, i - start + 0x40).length, appStore.configuration?.uart);
      appendStatus("恢复进度：" + (((i - start) / input.files[0].size) * 100).toFixed(1) + "%");
      scrollStatus();
    }
    state.status = state.status + "恢复进度：100.0%<br/>";
    await eeprom_reboot(appStore.connectPort);
  };
  input.click();
}

const backupConfig = async() => {
  if(appStore.connectState != true){alert(sessionStorage.getItem('noticeConnectK5')); return;};
  await backupRange(0, 0x1E00, 'config.bin')
}
const backupCalibration = async() => {
  if(appStore.connectState != true){alert(sessionStorage.getItem('noticeConnectK5')); return;};
  await backupRange(0x1E00, 0x2000, 'calibration.bin')
}

const restoreConfig = async() =>{
  if(appStore.connectState != true){alert(sessionStorage.getItem('noticeConnectK5')); return;};
  await restoreRange()
}
const restoreCalibration = async() =>{
  if(appStore.connectState != true){alert(sessionStorage.getItem('noticeConnectK5')); return;};
  await restoreRange(0x1E00)
}

const backup = async() => {
  if(appStore.connectState != true){alert(sessionStorage.getItem('noticeConnectK5')); return;};
  // 前置校验：未选择 EEPROM 大小时阻止备份流程，不生成备份文件
  if(!state.eepromType){
    const noticeDialog = DialogPlugin.confirm({
      theme: 'danger',
      header: '提示',
      body: '您未选择EEPROM大小！',
      className: 't-dialog-new-class1 t-dialog-new-class2',
      style: 'color: rgba(0, 0, 0, 0.6)',
      confirmBtn: '我知道了',
      cancelBtn: null,
      closeBtn: false,
      onConfirm: () => { noticeDialog.destroy() },
      closeOnEscKeydown: false,
      closeOnOverlayClick: false
    })
    return;
  }
  let _max = 0x2000;
  switch(state.eepromType){
    case "1":
      _max = 0x2000;
      break;
    case "2":
      _max = 0x20000;
      break;
    case "3":
      _max = 0x40000;
      break;
    case "4":
      _max = 0x80000;
      break;
    case '5':
      _max = 0x10000;
      break;
    default:
      _max = 0x2000;
  }
  if(appStore.configuration?.uart == "official" && _max >= 0x20000){
    alert('该固件不支持备份扩容空间');
    return;
  }
  await backupRange(0, _max)
}

const restore = async() => {
  if(appStore.connectState != true){alert(sessionStorage.getItem('noticeConnectK5')); return;};
  await restoreRange(parseInt(state.startInfo))
}
</script>

<script lang="ts">
  export default {
    name: 'Backup',
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
