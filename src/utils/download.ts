/**
 * 统一的“下载/保存文件”入口。
 *
 * 规则（任务④）：
 *  1. 每次下载或保存前，必须先让用户指定目标目录/文件名；
 *  2. 用户取消或未选择目录时，不执行下载或保存，也不回退到默认目录。
 *
 * 三条路径：
 *  - 安卓 APK（注入了 k5-bridge.js）：走原生“另存为”对话框
 *    （ACTION_CREATE_DOCUMENT，由用户挑选目录与文件名），取消即中止；
 *  - 支持 File System Access API 的浏览器（Chrome/Edge 等）：走 showSaveFilePicker，
 *    由用户指定目录与文件名，取消（AbortError）即中止；
 *  - 其余浏览器：退化为标准 <a download>，由浏览器自身的保存流程接管。
 */

interface SaveResult {
  saved: boolean;
  cancelled?: boolean;
}

declare global {
  interface Window {
    __K5USB?: {
      saveBlob?: (blob: Blob, name: string) => Promise<SaveResult>;
    };
    showSaveFilePicker?: (options?: any) => Promise<any>;
  }
}

function triggerAnchorDownload(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function fallbackMime(fileName: string): string {
  const ext = fileName.split('.').pop()?.toLowerCase() || '';
  switch (ext) {
    case 'bin':
      return 'application/octet-stream';
    case 'xlsx':
      return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
    case 'json':
      return 'application/json';
    case 'jpg':
    case 'jpeg':
      return 'image/jpeg';
    case 'png':
      return 'image/png';
    default:
      return 'application/octet-stream';
  }
}

/**
 * 把 Blob 保存为文件。返回是否已保存；用户取消返回 false，且不做任何兜底下载。
 */
export async function saveBlobAs(blob: Blob, fileName: string): Promise<boolean> {
  const name = (fileName || 'download.bin').trim();

  // 1) 安卓 APK：原生另存为对话框（由用户指定目录），取消即中止。
  if (typeof window !== 'undefined' && window.__K5USB?.saveBlob) {
    const res = await window.__K5USB.saveBlob(blob, name);
    return !!(res && res.saved);
  }

  // 2) File System Access API：先弹目录/文件选择，取消即中止。
  if (typeof window !== 'undefined' && typeof window.showSaveFilePicker === 'function') {
    try {
      const handle = await window.showSaveFilePicker({
        suggestedName: name,
        types: [
          {
            description: name.split('.').pop()?.toUpperCase() || 'FILE',
            accept: { [blob.type || fallbackMime(name)]: [name] },
          },
        ],
      });
      const writable = await handle.createWritable();
      await writable.write(blob);
      await writable.close();
      return true;
    } catch (e: any) {
      // 用户取消选择目录：直接中止，绝不回退到 <a download> 的默认目录。
      if (e && (e.name === 'AbortError' || e.name === 'NotAllowedError')) {
        return false;
      }
      // 其他异常也不做默认目录兜底，按取消处理。
      console.warn('saveBlobAs: showSaveFilePicker 失败，已中止保存', e);
      return false;
    }
  }

  // 3) 不支持目录选择的浏览器：交由浏览器保存流程（浏览器侧询问保存位置）。
  triggerAnchorDownload(blob, name);
  return true;
}

/**
 * 把 data: URL（如 canvas.toDataURL）保存为文件，同样遵循“先选目录、取消即中止”。
 */
export async function saveDataUrlAs(dataUrl: string, fileName: string): Promise<boolean> {
  const comma = dataUrl.indexOf(',');
  const meta = comma >= 0 ? dataUrl.slice(0, comma) : '';
  const mime = /data:([^;]+)/.exec(meta)?.[1] || 'application/octet-stream';
  const b64 = comma >= 0 ? dataUrl.slice(comma + 1) : '';
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i += 1) {
    bytes[i] = bin.charCodeAt(i);
  }
  return saveBlobAs(new Blob([bytes], { type: mime }), fileName);
}

/**
 * 下载远程 URL：先取回内容，再走统一的“先选目录”保存流程。
 */
export async function downloadUrlAs(url: string, fileName: string): Promise<boolean> {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`下载失败：HTTP ${res.status}`);
  }
  const blob = await res.blob();
  return saveBlobAs(blob, fileName);
}
