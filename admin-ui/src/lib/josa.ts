function hasBatchim(word: string): boolean | null {
  const ch = word.trim().slice(-1);
  const code = ch.charCodeAt(0) - 0xac00;
  if (code < 0 || code > 11171) return null;
  return code % 28 !== 0;
}
/** 목적격 조사. 한글이 아니면 '을'. */
export const eulReul = (w: string): '을' | '를' => (hasBatchim(w) === false ? '를' : '을');
/** 주격 조사. 한글이 아니면 '이'. */
export const iGa = (w: string): '이' | '가' => (hasBatchim(w) === false ? '가' : '이');
