import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Capacitor, type PluginListenerHandle } from '@capacitor/core'
import './App.css'
import { NetScanner, type EscPosIdentity, type NetworkInfo, type RawDevice, type ScanProgress } from './lib/netScanner'
import { KIND_ICON, KIND_LABEL, PORT_LABEL, describe, serviceLabel, sortViews, type DeviceView } from './lib/classify'

const CACHE_KEY = 'mesafing_last_scan'
const isNative = Capacitor.isNativePlatform()

type Cache = { at: number; network: NetworkInfo | null; devices: RawDevice[] }

function loadCache(): Cache | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    return raw ? (JSON.parse(raw) as Cache) : null
  } catch {
    return null
  }
}

function errText(e: unknown): string {
  if (e && typeof e === 'object' && 'message' in e) return String((e as { message: unknown }).message)
  return String(e)
}

async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  }
}

function webPort(d: RawDevice): number | null {
  if (d.httpPort) return Number(d.httpPort)
  for (const p of [80, 8080, 8000, 8081, 443, 8443]) if (d.ports.includes(p)) return p
  return null
}

function phaseText(p: ScanProgress): string {
  if (p.phase === 'sweep') return `Buscando equipos… ${p.done}/${p.total}`
  if (p.phase === 'details') return `Identificando modelos… ${p.done}/${p.total}`
  return 'Listo'
}

export default function App() {
  const cached = useMemo(loadCache, [])
  const [network, setNetwork] = useState<NetworkInfo | null>(cached?.network ?? null)
  const [netError, setNetError] = useState<string | null>(null)
  const [devices, setDevices] = useState<Map<string, RawDevice>>(
    () => new Map((cached?.devices ?? []).map((d) => [d.ip, d])),
  )
  const [lastScanAt, setLastScanAt] = useState<number | null>(cached?.at ?? null)
  const [scanning, setScanning] = useState(false)
  const [progress, setProgress] = useState<ScanProgress | null>(null)
  const [filter, setFilter] = useState<'all' | 'printers'>('all')
  const [expanded, setExpanded] = useState<string | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const [manualIp, setManualIp] = useState('')
  const [busy, setBusy] = useState<string | null>(null)
  const [escpos, setEscpos] = useState<Record<string, EscPosIdentity>>({})
  const listeners = useRef<PluginListenerHandle[]>([])
  const scanLock = useRef(false)
  const toastTimer = useRef<number | undefined>(undefined)

  const showToast = useCallback((msg: string) => {
    setToast(msg)
    window.clearTimeout(toastTimer.current)
    toastTimer.current = window.setTimeout(() => setToast(null), 3500)
  }, [])

  const refreshNetwork = useCallback(async () => {
    if (!isNative) return null
    try {
      const info = await NetScanner.getNetworkInfo()
      setNetwork(info)
      setNetError(null)
      return info
    } catch (e) {
      setNetError(errText(e))
      return null
    }
  }, [])

  useEffect(() => {
    void refreshNetwork()
    return () => {
      for (const l of listeners.current) void l.remove()
      listeners.current = []
    }
  }, [refreshNetwork])

  const upsert = useCallback((d: RawDevice) => {
    setDevices((prev) => {
      const next = new Map(prev)
      next.set(d.ip, d)
      return next
    })
  }, [])

  const startScan = useCallback(async () => {
    // El estado `scanning` recién se actualiza en el próximo render; el ref bloquea toques dobles al instante.
    if (!isNative || scanLock.current) return
    scanLock.current = true
    try {
      const info = await refreshNetwork()
      if (!info) return
      setScanning(true)
      setDevices(new Map())
      setEscpos({})
      setExpanded(null)
      setProgress({ phase: 'sweep', done: 0, total: info.hostCount, found: 0 })
      listeners.current.push(await NetScanner.addListener('device', upsert))
      listeners.current.push(await NetScanner.addListener('scanProgress', setProgress))
      try {
        const res = await NetScanner.scan()
        const map = new Map(res.devices.map((d) => [d.ip, d]))
        setDevices(map)
        setNetwork(res.network)
        const at = Date.now()
        setLastScanAt(at)
        localStorage.setItem(CACHE_KEY, JSON.stringify({ at, network: res.network, devices: res.devices } satisfies Cache))
        if (res.cancelled) showToast('Escaneo detenido')
      } catch (e) {
        showToast(errText(e))
      } finally {
        for (const l of listeners.current) void l.remove()
        listeners.current = []
        setScanning(false)
        setProgress(null)
      }
    } finally {
      scanLock.current = false
    }
  }, [refreshNetwork, showToast, upsert])

  const stopScan = useCallback(() => {
    void NetScanner.stopScan()
  }, [])

  const probeManual = useCallback(async () => {
    const host = manualIp.trim()
    if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) {
      showToast('Escribí una IP válida, ej. 192.168.1.100')
      return
    }
    setBusy(`probe:${host}`)
    try {
      const d = await NetScanner.probeHost({ host })
      if (d.alive) {
        upsert(d)
        setExpanded(d.ip)
        showToast(`${host} responde`)
      } else {
        showToast(`${host} no responde`)
      }
    } catch (e) {
      showToast(errText(e))
    } finally {
      setBusy(null)
    }
  }, [manualIp, showToast, upsert])

  const testPrint = useCallback(
    async (ip: string) => {
      setBusy(`print:${ip}`)
      try {
        await NetScanner.testPrint({ host: ip, port: 9100 })
        showToast(`Prueba enviada a ${ip}:9100`)
      } catch (e) {
        showToast(errText(e))
      } finally {
        setBusy(null)
      }
    },
    [showToast],
  )

  const identify = useCallback(
    async (ip: string) => {
      setBusy(`id:${ip}`)
      try {
        const id = await NetScanner.identifyEscPos({ host: ip, port: 9100 })
        setEscpos((prev) => ({ ...prev, [ip]: id }))
        if (!id.maker && !id.model && !id.modelId) showToast('La impresora no respondió a la identificación')
      } catch (e) {
        showToast(errText(e))
      } finally {
        setBusy(null)
      }
    },
    [showToast],
  )

  const copyIp = useCallback(
    async (ip: string) => {
      showToast((await copyText(ip)) ? `IP ${ip} copiada` : 'No se pudo copiar')
    },
    [showToast],
  )

  const views = useMemo(() => sortViews([...devices.values()].filter((d) => d.alive).map(describe)), [devices])
  const printers = views.filter((v) => v.kind === 'printer')
  const shown = filter === 'printers' ? printers : views
  const pct = progress && progress.total > 0 ? Math.round((progress.done / progress.total) * 100) : 0

  return (
    <div className="app">
      <header className="top">
        <div className="brand">
          <span className="logo" aria-hidden>
            📡
          </span>
          <div>
            <h1>mesafing</h1>
            <p>Encontrá la IP de tu impresora</p>
          </div>
        </div>
      </header>

      {!isNative && (
        <div className="notice">
          Esta vista es solo de prueba: el escaneo de red funciona dentro de la app Android instalada en el teléfono.
        </div>
      )}

      <section className="card net">
        {network ? (
          <>
            <div className="net-row">
              <span className="label">{network.transport === 'wifi' ? 'WiFi' : 'Red'}</span>
              <strong>{network.ssid ?? 'Red conectada'}</strong>
            </div>
            <div className="net-grid">
              <div>
                <span className="label">Tu IP</span>
                <span className="mono">{network.ip}</span>
              </div>
              <div>
                <span className="label">Router</span>
                <span className="mono">{network.gateway ?? '—'}</span>
              </div>
              <div>
                <span className="label">Rango</span>
                <span className="mono">
                  {network.firstHost} – {network.lastHost.split('.').pop()}
                </span>
              </div>
            </div>
          </>
        ) : (
          <p className="muted">{netError ?? (isNative ? 'Leyendo la red…' : 'Sin datos de red')}</p>
        )}
        {netError && network && <p className="error">{netError}</p>}

        {scanning ? (
          <div className="scan-box">
            <div className="bar">
              <div className="bar-fill" style={{ width: `${pct}%` }} />
            </div>
            <div className="scan-row">
              <span>{progress ? phaseText(progress) : 'Iniciando…'}</span>
              <span>{progress?.found ?? devices.size} encontrados</span>
            </div>
            <button className="btn ghost" onClick={stopScan}>
              Detener
            </button>
          </div>
        ) : (
          <button className="btn primary big" onClick={() => void startScan()} disabled={!isNative}>
            {views.length ? 'Escanear de nuevo' : 'Escanear red'}
          </button>
        )}
        {!scanning && lastScanAt && (
          <p className="muted small">Último escaneo: {new Date(lastScanAt).toLocaleString('es-AR')}</p>
        )}
      </section>

      {printers.length > 0 && !scanning && (
        <section className="card highlight">
          <strong>🖨️ {printers.length === 1 ? 'Impresora encontrada' : `${printers.length} impresoras encontradas`}</strong>
          {printers.map((p) => (
            <div key={p.raw.ip} className="hl-row">
              <span className="mono big-ip">{p.raw.ip}</span>
              <span className="hl-model">{p.title}</span>
              {p.boleKdsReady && <span className="tag ok">puerto 9100 · sirve para bole-kds</span>}
            </div>
          ))}
        </section>
      )}

      <div className="toolbar">
        <div className="seg">
          <button className={filter === 'all' ? 'on' : ''} onClick={() => setFilter('all')}>
            Todos ({views.length})
          </button>
          <button className={filter === 'printers' ? 'on' : ''} onClick={() => setFilter('printers')}>
            Impresoras ({printers.length})
          </button>
        </div>
      </div>

      <ul className="list">
        {shown.map((v) => (
          <DeviceCard
            key={v.raw.ip}
            view={v}
            open={expanded === v.raw.ip}
            busy={busy}
            escpos={escpos[v.raw.ip]}
            onToggle={() => setExpanded((cur) => (cur === v.raw.ip ? null : v.raw.ip))}
            onCopy={() => void copyIp(v.raw.ip)}
            onTestPrint={() => void testPrint(v.raw.ip)}
            onIdentify={() => void identify(v.raw.ip)}
            onOpenWeb={(url) => void NetScanner.openUrl({ url }).catch((e) => showToast(errText(e)))}
          />
        ))}
        {!shown.length && !scanning && (
          <li className="empty">
            {filter === 'printers' && views.length
              ? 'No se detectó ninguna impresora. Revisá que esté encendida y en el mismo WiFi, o probá su IP abajo.'
              : 'Tocá “Escanear red” para ver los equipos conectados.'}
          </li>
        )}
      </ul>

      <section className="card manual">
        <span className="label">Probar una IP puntual</span>
        <div className="manual-row">
          <input
            inputMode="decimal"
            placeholder="192.168.1.100"
            value={manualIp}
            onChange={(e) => setManualIp(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && void probeManual()}
          />
          <button className="btn" onClick={() => void probeManual()} disabled={!isNative || busy !== null}>
            {busy?.startsWith('probe:') ? 'Probando…' : 'Probar'}
          </button>
        </div>
        <p className="muted small">
          Tip: muchas térmicas imprimen su IP si las apagás y las prendés manteniendo apretado el botón FEED.
        </p>
      </section>

      {toast && <div className="toast">{toast}</div>}
    </div>
  )
}

type CardProps = {
  view: DeviceView
  open: boolean
  busy: string | null
  escpos?: EscPosIdentity
  onToggle: () => void
  onCopy: () => void
  onTestPrint: () => void
  onIdentify: () => void
  onOpenWeb: (url: string) => void
}

function DeviceCard({ view, open, busy, escpos, onToggle, onCopy, onTestPrint, onIdentify, onOpenWeb }: CardProps) {
  const d = view.raw
  const port = webPort(d)
  const webUrl = port ? `${port === 443 || port === 8443 ? 'https' : 'http'}://${d.ip}${port === 80 || port === 443 ? '' : `:${port}`}/` : null
  const details: [string, string | undefined][] = [
    ['Tipo', KIND_LABEL[view.kind]],
    ['Nombre en el router', d.routerName],
    ['Conexión', d.link],
    ['Modelo impresora', d.printerModel],
    ['Modelo (mDNS)', d.mdnsModel],
    ['Nombre (mDNS)', d.mdnsName],
    ['UPnP', [d.upnpManufacturer, d.upnpModel].filter(Boolean).join(' ') || undefined],
    ['Nombre UPnP', d.upnpName],
    ['SNMP', d.snmpDescr],
    ['SNMP dispositivo', d.snmpDevice],
    ['SNMP nombre', d.snmpName],
    ['Nombre Windows', d.netbiosName],
    ['Hostname', d.hostname],
    ['MAC', d.mac],
    ['Página web', d.httpTitle],
    ['Servidor web', d.httpServer ?? d.upnpServer],
    ['Detectado por', d.sources.join(', ')],
  ]

  return (
    <li className={`device ${view.kind} ${open ? 'open' : ''}`}>
      <button className="device-head" onClick={onToggle}>
        <span className="icon" aria-hidden>
          {KIND_ICON[view.kind]}
        </span>
        <span className="info">
          <span className="title">{view.title}</span>
          {view.subtitle && <span className="subtitle">{view.subtitle}</span>}
          <span className="ip mono">{d.ip}</span>
        </span>
        {view.boleKdsReady && <span className="tag ok">9100</span>}
      </button>

      {open && (
        <div className="device-body">
          {view.boleKdsReady && (
            <p className="ok-text">
              Puerto 9100 abierto → en bole-kds poné IP <b className="mono">{d.ip}</b> y puerto <b>9100</b>.
            </p>
          )}
          <div className="actions">
            <button className="btn primary" onClick={onCopy}>
              Copiar IP
            </button>
            {view.boleKdsReady && (
              <>
                <button className="btn" onClick={onTestPrint} disabled={busy !== null}>
                  {busy === `print:${d.ip}` ? 'Imprimiendo…' : 'Imprimir prueba'}
                </button>
                <button className="btn" onClick={onIdentify} disabled={busy !== null}>
                  {busy === `id:${d.ip}` ? 'Consultando…' : 'Identificar modelo'}
                </button>
              </>
            )}
            {webUrl && (
              <button className="btn" onClick={() => onOpenWeb(webUrl)}>
                Abrir panel web
              </button>
            )}
          </div>

          {escpos && (escpos.maker || escpos.model || escpos.modelId) && (
            <div className="escpos">
              <span className="label">Respuesta ESC/POS</span>
              <span>
                {[escpos.maker, escpos.model].filter(Boolean).join(' ') || 'Modelo sin nombre'}
                {escpos.modelId ? ` · id ${escpos.modelId}` : ''}
                {escpos.firmware ? ` · fw ${escpos.firmware}` : ''}
              </span>
            </div>
          )}

          <dl className="details">
            {details
              .filter(([, v]) => v)
              .map(([k, v]) => (
                <div key={k}>
                  <dt>{k}</dt>
                  <dd>{v}</dd>
                </div>
              ))}
          </dl>

          {d.ports.length > 0 && (
            <div className="chips">
              <span className="label">Puertos abiertos</span>
              <div>
                {d.ports.map((p) => (
                  <span key={p} className={`chip ${p === 9100 ? 'ok' : ''}`}>
                    {p}
                    {PORT_LABEL[p] ? ` · ${PORT_LABEL[p]}` : ''}
                  </span>
                ))}
              </div>
            </div>
          )}
          {d.services.length > 0 && (
            <div className="chips">
              <span className="label">Servicios</span>
              <div>
                {d.services.map((s) => (
                  <span key={s} className="chip">
                    {serviceLabel(s)}
                  </span>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </li>
  )
}
