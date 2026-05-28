// FunnelTab (pages-3.jsx) 이 기대하는 형태.
// 디자인 data.js FUNNEL 구조 그대로.

export type FunnelSeries = {
  day: string;
  attempts: number;
  success: number;
};

export type FunnelByEventType = {
  type: string;
  n: number;
};

export type FunnelData = {
  windowDays: number;
  registration: { attempts: number; success: number; ratio: number };
  authentication: { attempts: number; success: number; ratio: number };
  conversion: number;
  series: FunnelSeries[];
  byEventType: FunnelByEventType[];
};

// 7-day fixture — from data.js FUNNEL
const funnel7d: FunnelData = {
  windowDays: 7,
  registration: { attempts: 4218, success: 4039, ratio: 0.957 },
  authentication: { attempts: 84102, success: 83217, ratio: 0.9895 },
  conversion: 0.823,
  series: [
    { day: '월', attempts: 11200, success: 11058 },
    { day: '화', attempts: 12104, success: 11991 },
    { day: '수', attempts: 11894, success: 11770 },
    { day: '목', attempts: 12340, success: 12180 },
    { day: '금', attempts: 13702, success: 13510 },
    { day: '토', attempts: 11588, success: 11488 },
    { day: '일', attempts: 11274, success: 11220 },
  ],
  byEventType: [
    { type: 'REG_ATTEMPT',  n: 4218  },
    { type: 'REG_SUCCESS',  n: 4039  },
    { type: 'AUTH_ATTEMPT', n: 84102 },
    { type: 'AUTH_SUCCESS', n: 83217 },
    { type: 'REG_FAIL',     n: 179   },
    { type: 'AUTH_FAIL',    n: 885   },
  ],
};

// 1-day fixture — scale ~1/7 of 7d
const funnel1d: FunnelData = {
  windowDays: 1,
  registration: { attempts: 602, success: 577, ratio: 0.958 },
  authentication: { attempts: 12014, success: 11888, ratio: 0.9895 },
  conversion: 0.823,
  series: [
    { day: '00', attempts: 480, success: 475 },
    { day: '03', attempts: 390, success: 386 },
    { day: '06', attempts: 520, success: 514 },
    { day: '09', attempts: 1840, success: 1821 },
    { day: '12', attempts: 2250, success: 2228 },
    { day: '15', attempts: 2410, success: 2384 },
    { day: '18', attempts: 2380, success: 2352 },
    { day: '21', attempts: 1744, success: 1728 },
  ],
  byEventType: [
    { type: 'REG_ATTEMPT',  n: 602   },
    { type: 'REG_SUCCESS',  n: 577   },
    { type: 'AUTH_ATTEMPT', n: 12014 },
    { type: 'AUTH_SUCCESS', n: 11888 },
    { type: 'REG_FAIL',     n: 25    },
    { type: 'AUTH_FAIL',    n: 126   },
  ],
};

// 30-day fixture — scale ~4.3x of 7d
const funnel30d: FunnelData = {
  windowDays: 30,
  registration: { attempts: 18074, success: 17310, ratio: 0.957 },
  authentication: { attempts: 360437, success: 356612, ratio: 0.9894 },
  conversion: 0.821,
  series: [
    { day: 'W1', attempts: 77290, success: 76434 },
    { day: 'W2', attempts: 89102, success: 88141 },
    { day: 'W3', attempts: 96254, success: 95212 },
    { day: 'W4', attempts: 97791, success: 96825 },
  ],
  byEventType: [
    { type: 'REG_ATTEMPT',  n: 18074  },
    { type: 'REG_SUCCESS',  n: 17310  },
    { type: 'AUTH_ATTEMPT', n: 360437 },
    { type: 'AUTH_SUCCESS', n: 356612 },
    { type: 'REG_FAIL',     n: 764    },
    { type: 'AUTH_FAIL',    n: 3825   },
  ],
};

const byWindow: Record<number, FunnelData> = {
  1:  funnel1d,
  7:  funnel7d,
  30: funnel30d,
};

export function getFunnel(tenantId: string, windowDays: 1 | 7 | 30 = 7): FunnelData {
  // All tenants share the same funnel shape for now.
  // tenantId param is reserved for future per-tenant data.
  void tenantId;
  return byWindow[windowDays] ?? funnel7d;
}
