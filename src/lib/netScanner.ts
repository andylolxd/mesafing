import { registerPlugin, type PluginListenerHandle } from '@capacitor/core'

export type NetworkInfo = {
  transport: 'wifi' | 'ethernet'
  ip: string
  prefixLength: number
  gateway?: string
  ssid?: string
  networkAddress: string
  scanPrefix: number
  hostCount: number
  firstHost: string
  lastHost: string
}

/** Lo que manda el plugin nativo por cada equipo; los campos opcionales dependen de qué respondió. */
export type RawDevice = {
  ip: string
  alive: boolean
  isSelf: boolean
  isGateway: boolean
  ports: number[]
  services: string[]
  sources: string[]
  deviceName?: string
  hostname?: string
  mac?: string
  snmpDescr?: string
  snmpName?: string
  snmpDevice?: string
  netbiosName?: string
  httpTitle?: string
  httpServer?: string
  httpPort?: string
  mdnsName?: string
  mdnsModel?: string
  printerModel?: string
  upnpName?: string
  upnpManufacturer?: string
  upnpModel?: string
  upnpServer?: string
  /** Nombre que muestra el router (app de Starlink). */
  routerName?: string
  link?: string
}

export type ScanProgress = {
  phase: 'sweep' | 'details' | 'done'
  done: number
  total: number
  found: number
}

export type ScanResult = {
  network: NetworkInfo
  devices: RawDevice[]
  cancelled: boolean
}

export type EscPosIdentity = {
  modelId?: string | null
  maker?: string | null
  model?: string | null
  firmware?: string | null
}

type NetScannerPlugin = {
  getNetworkInfo(): Promise<NetworkInfo>
  scan(): Promise<ScanResult>
  stopScan(): Promise<void>
  probeHost(opts: { host: string }): Promise<RawDevice>
  testPrint(opts: { host: string; port?: number }): Promise<void>
  identifyEscPos(opts: { host: string; port?: number }): Promise<EscPosIdentity>
  openUrl(opts: { url: string }): Promise<void>
  addListener(event: 'device', fn: (d: RawDevice) => void): Promise<PluginListenerHandle>
  addListener(event: 'scanProgress', fn: (p: ScanProgress) => void): Promise<PluginListenerHandle>
}

export const NetScanner = registerPlugin<NetScannerPlugin>('NetScanner')
