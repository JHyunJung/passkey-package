/* global React */
// Minimal stroke icon set. Pass size; inherits currentColor.
// Each icon is built from path strings to avoid JSX parser quirks with nested fragments.

function makeIcon(paths, fillPaths) {
  return function IconComp(p) {
    const size = p.size || 16;
    const stroke = p.stroke || 1.6;
    const children = [];
    (paths || []).forEach((d, i) => {
      children.push(React.createElement("path", { key: "p" + i, d }));
    });
    (fillPaths || []).forEach((d, i) => {
      children.push(React.createElement("path", { key: "f" + i, d, fill: "currentColor", stroke: "none" }));
    });
    return React.createElement(
      "svg",
      {
        width: size, height: size, viewBox: "0 0 24 24",
        fill: "none", stroke: "currentColor", strokeWidth: stroke,
        strokeLinecap: "round", strokeLinejoin: "round",
        "aria-hidden": "true",
      },
      ...children
    );
  };
}

// Some icons need shapes other than <path> — use a richer factory.
function makeIconR(elements) {
  return function IconComp(p) {
    const size = p.size || 16;
    const stroke = p.stroke || 1.6;
    return React.createElement(
      "svg",
      {
        width: size, height: size, viewBox: "0 0 24 24",
        fill: "none", stroke: "currentColor", strokeWidth: stroke,
        strokeLinecap: "round", strokeLinejoin: "round",
        "aria-hidden": "true",
      },
      ...elements.map((e, i) => React.createElement(e.tag, { key: i, ...e.attrs }))
    );
  };
}

const Icons = {
  Shield: makeIcon(["M12 3l8 3v6c0 5-3.5 8.5-8 9-4.5-.5-8-4-8-9V6l8-3z"]),
  Key: makeIconR([
    { tag: "circle", attrs: { cx: 8, cy: 15, r: 3 } },
    { tag: "path", attrs: { d: "M10.5 13l9-9M16 8l3 3" } },
  ]),
  Users: makeIconR([
    { tag: "circle", attrs: { cx: 9, cy: 9, r: 3.5 } },
    { tag: "path", attrs: { d: "M3 19c0-3.3 2.7-6 6-6s6 2.7 6 6" } },
    { tag: "path", attrs: { d: "M16 4a3 3 0 010 6M17 19c0-2 .5-3.7 1-5" } },
  ]),
  Building: makeIcon([
    "M4 21V5a2 2 0 012-2h8a2 2 0 012 2v16",
    "M16 8h2a2 2 0 012 2v11",
    "M8 7h2M8 11h2M8 15h2M14 11h2M14 15h2",
  ]),
  Cog: makeIconR([
    { tag: "circle", attrs: { cx: 12, cy: 12, r: 3 } },
    { tag: "path", attrs: { d: "M19.4 15a1.7 1.7 0 00.3 1.8l.1.1a2 2 0 01-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.8-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 11-4 0v-.1a1.7 1.7 0 00-1-1.5 1.7 1.7 0 00-1.8.3l-.1.1a2 2 0 01-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.8 1.7 1.7 0 00-1.5-1H3a2 2 0 110-4h.1a1.7 1.7 0 001.5-1 1.7 1.7 0 00-.3-1.8l-.1-.1a2 2 0 012.8-2.8l.1.1a1.7 1.7 0 001.8.3H9a1.7 1.7 0 001-1.5V3a2 2 0 014 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.8-.3l.1-.1a2 2 0 012.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.8V9a1.7 1.7 0 001.5 1H21a2 2 0 010 4h-.1a1.7 1.7 0 00-1.5 1z" } },
  ]),
  Activity: makeIcon(["M3 12h4l3-8 4 16 3-8h4"]),
  Receipt: makeIcon([
    "M4 4h16v18l-3-2-3 2-3-2-3 2-3-2-1 2V4z",
    "M8 9h8M8 13h8M8 17h5",
  ]),
  Filter: makeIcon(["M3 4h18l-7 9v7l-4-2v-5L3 4z"]),
  Search: makeIconR([
    { tag: "circle", attrs: { cx: 11, cy: 11, r: 7 } },
    { tag: "path", attrs: { d: "M21 21l-4.3-4.3" } },
  ]),
  Plus: makeIcon(["M12 5v14M5 12h14"]),
  X: makeIcon(["M6 6l12 12M18 6L6 18"]),
  Check: makeIcon(["M5 12l5 5L20 7"]),
  ChevronDown: makeIcon(["M6 9l6 6 6-6"]),
  ChevronRight: makeIcon(["M9 6l6 6-6 6"]),
  ChevronLeft: makeIcon(["M15 6l-6 6 6 6"]),
  ChevronUp: makeIcon(["M6 15l6-6 6 6"]),
  Copy: makeIconR([
    { tag: "rect", attrs: { x: 9, y: 9, width: 11, height: 11, rx: 2 } },
    { tag: "path", attrs: { d: "M5 15V5a2 2 0 012-2h10" } },
  ]),
  Eye: makeIconR([
    { tag: "path", attrs: { d: "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" } },
    { tag: "circle", attrs: { cx: 12, cy: 12, r: 3 } },
  ]),
  EyeOff: makeIcon([
    "M3 3l18 18",
    "M10.6 6.1A10 10 0 0112 6c6.5 0 10 6 10 6a17.3 17.3 0 01-3.7 4.6",
    "M6.2 6.2A17.3 17.3 0 002 12s3.5 6 10 6a10 10 0 003.5-.6",
  ]),
  Trash: makeIcon([
    "M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2",
    "M5 6l1 14a2 2 0 002 2h8a2 2 0 002-2l1-14",
  ]),
  Alert: makeIcon([
    "M12 3l10 18H2L12 3z",
    "M12 10v4M12 18h.01",
  ]),
  Info: makeIconR([
    { tag: "circle", attrs: { cx: 12, cy: 12, r: 9 } },
    { tag: "path", attrs: { d: "M12 11v6M12 8h.01" } },
  ]),
  Lock: makeIconR([
    { tag: "rect", attrs: { x: 4, y: 11, width: 16, height: 10, rx: 2 } },
    { tag: "path", attrs: { d: "M8 11V7a4 4 0 018 0v4" } },
  ]),
  LogOut: makeIcon([
    "M9 3H5a2 2 0 00-2 2v14a2 2 0 002 2h4",
    "M16 17l5-5-5-5M21 12H9",
  ]),
  Refresh: makeIcon(["M21 12a9 9 0 11-3-6.7L21 8M21 3v5h-5"]),
  Download: makeIcon(["M12 3v12m0 0l-4-4m4 4l4-4M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2"]),
  ExternalLink: makeIcon([
    "M14 4h6v6",
    "M20 4L11 13",
    "M19 14v5a2 2 0 01-2 2H5a2 2 0 01-2-2V7a2 2 0 012-2h5",
  ]),
  Fingerprint: makeIcon(["M6 11a6 6 0 0112 0M5 16c2-2 3-4 3-6M19 11c0 4-2 7-4 9M12 11v3c0 2-1 4-2 6M9 21c2-2 3-5 3-9"]),
  Link: makeIcon([
    "M10 14a4 4 0 005.7 0l4.3-4.3a4 4 0 00-5.7-5.7L13 5.3",
    "M14 10a4 4 0 00-5.7 0L4 14.3a4 4 0 005.7 5.7L11 18.7",
  ]),
  Globe: makeIconR([
    { tag: "circle", attrs: { cx: 12, cy: 12, r: 9 } },
    { tag: "path", attrs: { d: "M3 12h18M12 3a14 14 0 010 18M12 3a14 14 0 000 18" } },
  ]),
  Calendar: makeIconR([
    { tag: "rect", attrs: { x: 3, y: 5, width: 18, height: 16, rx: 2 } },
    { tag: "path", attrs: { d: "M16 3v4M8 3v4M3 11h18" } },
  ]),
  Hash: makeIcon(["M4 9h16M4 15h16M10 3L8 21M16 3l-2 18"]),
  Menu: makeIcon(["M4 6h16M4 12h16M4 18h16"]),
  Dots: makeIconR([
    { tag: "circle", attrs: { cx: 12, cy: 5, r: 1.5, fill: "currentColor", stroke: "none" } },
    { tag: "circle", attrs: { cx: 12, cy: 12, r: 1.5, fill: "currentColor", stroke: "none" } },
    { tag: "circle", attrs: { cx: 12, cy: 19, r: 1.5, fill: "currentColor", stroke: "none" } },
  ]),
  Sparkles: makeIcon(["M12 3v4M12 17v4M3 12h4M17 12h4M5.6 5.6l2.8 2.8M15.6 15.6l2.8 2.8M5.6 18.4l2.8-2.8M15.6 8.4l2.8-2.8"]),
};

// Brand mark — solid color block with a stylized "P" and dot.
const BrandMark = ({ size = 22 }) => React.createElement(
  "svg",
  { width: size, height: size, viewBox: "0 0 32 32", "aria-hidden": "true" },
  React.createElement("rect", { x: 1, y: 1, width: 30, height: 30, rx: 7, fill: "#4f46e5" }),
  React.createElement("path", {
    d: "M10 8h7a5 5 0 010 10h-3v6h-4V8zm4 4v2.5h2.5a1.25 1.25 0 000-2.5H14z",
    fill: "white",
  }),
  React.createElement("circle", { cx: 22, cy: 22, r: 2.6, fill: "white" })
);

window.Icons = Icons;
window.BrandMark = BrandMark;
