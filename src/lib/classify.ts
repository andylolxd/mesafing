import type { RawDevice } from './netScanner'

export type DeviceKind =
  | 'self'
  | 'printer'
  | 'router'
  | 'phone'
  | 'computer'
  | 'tv'
  | 'camera'
  | 'nas'
  | 'iot'
  | 'unknown'

export const KIND_LABEL: Record<DeviceKind, string> = {
  self: 'Este teléfono',
  printer: 'Impresora',
  router: 'Router / Access point',
  phone: 'Teléfono / Tablet',
  computer: 'Computadora',
  tv: 'TV / Streaming',
  camera: 'Cámara',
  nas: 'Almacenamiento (NAS)',
  iot: 'Dispositivo inteligente',
  unknown: 'Dispositivo',
}

export const KIND_ICON: Record<DeviceKind, string> = {
  self: '📱',
  printer: '🖨️',
  router: '📶',
  phone: '📱',
  computer: '💻',
  tv: '📺',
  camera: '📷',
  nas: '🗄️',
  iot: '💡',
  unknown: '❔',
}

export const PORT_LABEL: Record<number, string> = {
  21: 'FTP',
  22: 'SSH',
  23: 'Telnet',
  53: 'DNS',
  80: 'Web (HTTP)',
  139: 'NetBIOS',
  443: 'Web (HTTPS)',
  445: 'Carpetas compartidas (SMB)',
  515: 'Impresión LPD',
  548: 'AFP (Apple)',
  554: 'Video RTSP',
  631: 'Impresión IPP',
  1400: 'Sonos',
  1883: 'MQTT',
  3000: 'Web 3000',
  3389: 'Escritorio remoto',
  5000: 'UPnP / Web 5000',
  5357: 'WSD (Windows)',
  5900: 'VNC',
  7000: 'AirPlay',
  8000: 'Web 8000',
  8008: 'Chromecast',
  8009: 'Chromecast',
  8080: 'Web 8080',
  8081: 'Web 8081',
  8443: 'Web HTTPS 8443',
  8888: 'Web 8888',
  9000: 'Web 9000',
  9100: 'Impresión RAW / ESC/POS',
  49152: 'UPnP',
  62078: 'iPhone / iPad sync',
}

const SERVICE_LABEL: Record<string, string> = {
  '_ipp._tcp': 'Impresora (IPP)',
  '_ipps._tcp': 'Impresora (IPPS)',
  '_printer._tcp': 'Impresora (LPD)',
  '_pdl-datastream._tcp': 'Impresora (RAW 9100)',
  '_scanner._tcp': 'Escáner',
  '_uscan._tcp': 'Escáner',
  '_http._tcp': 'Web',
  '_googlecast._tcp': 'Chromecast',
  '_airplay._tcp': 'AirPlay',
  '_raop._tcp': 'AirPlay audio',
  '_smb._tcp': 'Carpetas compartidas',
  '_workstation._tcp': 'Computadora',
  '_device-info._tcp': 'Info Apple',
  '_companion-link._tcp': 'Apple',
  '_hap._tcp': 'HomeKit',
  '_spotify-connect._tcp': 'Spotify Connect',
  '_androidtvremote2._tcp': 'Android TV',
  '_sonos._tcp': 'Sonos',
  '_ssh._tcp': 'SSH',
  '_rfb._tcp': 'Pantalla remota',
}

export function serviceLabel(type: string): string {
  return SERVICE_LABEL[type] ?? type
}

const PRINTER_WORDS =
  /\b(printer|impresora|epson|xprinter|x-printer|bixolon|star\s?micronics|tsp\d|citizen|zebra|sewoo|rongta|hprt|gprinter|jetdirect|laserjet|deskjet|officejet|pixma|brother|kyocera|ricoh|lexmark|xerox|canon\s?(mf|lbp|g\d)|tm-?[tmu]\d|ecotank|l\d{4}|pos-?\d{2}|xp-?[a-z]?\d{2,3}|brn[0-9a-f]{6}|brw[0-9a-f]{6}|npi[0-9a-f]{6}|hp[0-9a-f]{6})/i
const ROUTER_WORDS =
  /\b(router|gateway|tp-?link|archer|deco|mikrotik|routeros|ubiquiti|unifi|airos|huawei\s?hg|fiberhome|zte|sagemcom|technicolor|arris|nokia\s?g-|mercusys|tenda|d-?link|netgear|asus\s?rt|linksys|openwrt|mesh|access point|ont\b|gpon)/i
const TV_WORDS = /\b(tv|chromecast|roku|fire\s?tv|bravia|webos|tizen|android\s?tv|google\s?tv|apple\s?tv|shield|smart\s?tv|sonos)/i
const CAMERA_WORDS = /\b(camera|cámara|camara|ipc|hikvision|dahua|ezviz|imou|reolink|tapo\s?c\d|yi\s?cam|nvr|dvr)\b/i
const NAS_WORDS = /\b(nas|synology|diskstation|qnap|truenas|wd\s?my\s?cloud)\b/i
const PHONE_WORDS = /\b(iphone|ipad|android|galaxy|pixel|redmi|xiaomi|motorola|moto\s?[a-z]\d|huawei\s?(p|mate|nova)|oppo|vivo|realme|oneplus)\b/i
const COMPUTER_WORDS = /\b(windows|macbook|imac|mac\s?mini|desktop|laptop|notebook|pc|ubuntu|linux|workstation|thinkpad)\b/i
const IOT_WORDS = /\b(esp\d+|tasmota|shelly|sonoff|tuya|smart\s?plug|bulb|alexa|echo|google\s?home|nest|homekit)\b/i

function allText(d: RawDevice): string {
  return [
    d.routerName,
    d.printerModel,
    d.mdnsModel,
    d.mdnsName,
    d.upnpModel,
    d.upnpName,
    d.upnpManufacturer,
    d.upnpServer,
    d.snmpDescr,
    d.snmpDevice,
    d.snmpName,
    d.netbiosName,
    d.httpTitle,
    d.httpServer,
    d.hostname,
    d.deviceName,
  ]
    .filter(Boolean)
    .join(' | ')
}

export function classify(d: RawDevice): DeviceKind {
  if (d.isSelf) return 'self'
  const ports = new Set(d.ports)
  const services = d.services
  const text = allText(d)

  if (
    d.printerModel ||
    ports.has(9100) ||
    ports.has(515) ||
    ports.has(631) ||
    services.some((s) => /_ipps?\._tcp|_printer\._tcp|_pdl-datastream/.test(s)) ||
    PRINTER_WORDS.test(text)
  ) {
    return 'printer'
  }
  if (d.isGateway || ROUTER_WORDS.test(text)) return 'router'
  if (CAMERA_WORDS.test(text) || (ports.has(554) && !ports.has(8008))) return 'camera'
  if (NAS_WORDS.test(text)) return 'nas'
  if (
    TV_WORDS.test(text) ||
    services.some((s) => /_googlecast|_androidtvremote|_airplay|_sonos/.test(s)) ||
    ports.has(8008) ||
    ports.has(8009)
  ) {
    return 'tv'
  }
  if (ports.has(62078) || services.includes('_companion-link._tcp') || PHONE_WORDS.test(text)) return 'phone'
  if (
    d.netbiosName ||
    ports.has(445) ||
    ports.has(3389) ||
    ports.has(5357) ||
    services.includes('_workstation._tcp') ||
    COMPUTER_WORDS.test(text)
  ) {
    return 'computer'
  }
  if (IOT_WORDS.test(text) || services.includes('_hap._tcp') || ports.has(1883)) return 'iot'
  return 'unknown'
}

function firstLine(s?: string): string | undefined {
  if (!s) return undefined
  const line = s.split(/[\r\n]/)[0].trim()
  return line.length > 70 ? `${line.slice(0, 67)}…` : line || undefined
}

function stripLocal(h?: string): string | undefined {
  if (!h) return undefined
  return h.replace(/\.(local|lan|home|localdomain)\.?$/i, '')
}

/** El mejor “modelo” disponible, en orden de confiabilidad. */
export function modelOf(d: RawDevice): string | undefined {
  const upnp =
    d.upnpModel && d.upnpManufacturer && !d.upnpModel.toLowerCase().includes(d.upnpManufacturer.toLowerCase().split(' ')[0])
      ? `${d.upnpManufacturer} ${d.upnpModel}`
      : d.upnpModel
  return (
    d.printerModel ||
    d.deviceName ||
    d.mdnsModel ||
    upnp ||
    firstLine(d.snmpDevice) ||
    firstLine(d.snmpDescr) ||
    undefined
  )
}

export function nameOf(d: RawDevice): string | undefined {
  return (
    d.routerName ||
    d.mdnsName ||
    d.upnpName ||
    d.netbiosName ||
    d.snmpName ||
    stripLocal(d.hostname) ||
    firstLine(d.httpTitle) ||
    undefined
  )
}

export type DeviceView = {
  raw: RawDevice
  kind: DeviceKind
  title: string
  subtitle?: string
  boleKdsReady: boolean
}

export function describe(d: RawDevice): DeviceView {
  const kind = classify(d)
  const model = modelOf(d)
  const name = nameOf(d)
  let title: string
  let subtitle: string | undefined
  if (model && name && !model.toLowerCase().includes(name.toLowerCase()) && !name.toLowerCase().includes(model.toLowerCase())) {
    title = model
    subtitle = name
  } else {
    title = model || name || KIND_LABEL[kind]
    subtitle = model || name ? KIND_LABEL[kind] : undefined
  }
  if (d.isGateway && kind !== 'self') subtitle = subtitle ? `Router principal · ${subtitle}` : 'Router principal'
  if (d.link) subtitle = subtitle ? `${subtitle} · ${d.link}` : d.link
  return { raw: d, kind, title, subtitle, boleKdsReady: d.ports.includes(9100) }
}

export function ipSortKey(ip: string): number {
  return ip.split('.').reduce((acc, p) => acc * 256 + Number(p), 0)
}

export function sortViews(views: DeviceView[]): DeviceView[] {
  return [...views].sort((a, b) => {
    const ka = a.kind === 'printer' ? 0 : 1
    const kb = b.kind === 'printer' ? 0 : 1
    if (ka !== kb) return ka - kb
    return ipSortKey(a.raw.ip) - ipSortKey(b.raw.ip)
  })
}