// SystemInfoTab (pages-6.jsx) 이 기대하는 형태.
// pages-6 ComponentRow / KvLine 의 inline mock 그대로.

export type ComponentInfo = {
  name: string;
  version: string;
  status: 'OK' | 'DOWN' | 'DEGRADED';
  instances: number;
  note?: string;
};

export type HostInfo = {
  apiHostname: string;
  adminConsole: string;
  region: string;
  environment: string;
  deployMethod: string;
};

export type SystemInfoData = {
  serverVersion: string;
  deployedAt: string;
  apiP95Ms: number;
  apiAvgMs: number;
  apiP99Ms: number;
  uptimePercent: number;
  uptimeDays: number;
  uptimeIncidentMinutes: number;
  host: HostInfo;
  components: ComponentInfo[];
};

// pages-6 SystemInfoTab inline values
export const systemInfoFixture: SystemInfoData = {
  serverVersion:         'v1.0.4',
  deployedAt:            '2026-05-14T00:00:00Z',
  apiP95Ms:              42,
  apiAvgMs:              18,
  apiP99Ms:              89,
  uptimePercent:         99.97,
  uptimeDays:            30,
  uptimeIncidentMinutes: 8.6,

  host: {
    apiHostname:   'api.passkey.example.com',
    adminConsole:  'admin.passkey.example.com',
    region:        'ap-northeast-2 (Seoul)',
    environment:   'production',
    deployMethod:  'k8s · 1 admin replica · 4 API replica',
  },

  // ComponentRow entries from pages-6
  components: [
    {
      name:      'Crosscert Passkey API',
      version:   'v1.0.4',
      status:    'OK',
      instances: 4,
    },
    {
      name:      'PostgreSQL',
      version:   '16.4',
      status:    'OK',
      instances: 3,
      note:      'primary + 2 read replica',
    },
    {
      name:      'Redis (cache + pub/sub)',
      version:   '7.4',
      status:    'OK',
      instances: 3,
      note:      'cluster mode',
    },
    {
      name:      'FIDO MDS Sync',
      version:   '—',
      status:    'OK',
      instances: 1,
      note:      'daily 03:00 KST',
    },
  ],
};
