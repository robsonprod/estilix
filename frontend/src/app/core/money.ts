/**
 * Money on the screen vs. on the wire. The API sends and receives decimal strings with a dot
 * ("45.00"); people type and read "45,00" or "R$ 45,00". Never floats.
 */

/** "45.00" -> "R$ 45,00"; "1234.5" -> "R$ 1.234,50". */
export function brl(decimal: string | null | undefined): string {
  if (decimal == null || decimal === '') return '';
  const negative = decimal.startsWith('-');
  const [intPart, frac = ''] = decimal.replace('-', '').split('.');
  const grouped = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return `${negative ? '-' : ''}R$ ${grouped},${(frac + '00').slice(0, 2)}`;
}

/**
 * What the user typed -> API decimal ("45" -> "45.00", "45,5" -> "45.50", "1.234,56" -> "1234.56").
 * Returns null when it is not a non-negative amount with at most 2 decimals.
 */
export function toDecimal(typed: string | null | undefined): string | null {
  const text = (typed ?? '').replace(/R\$|\s/g, '');
  if (!text) return null;
  const normalized = text.includes(',') ? text.replace(/\./g, '').replace(',', '.') : text;
  if (!/^\d+(\.\d{1,2})?$/.test(normalized)) return null;
  const [i, f = ''] = normalized.split('.');
  return `${String(Number(i))}.${(f + '00').slice(0, 2)}`;
}

/** API decimal -> what the price input shows ("45.00" -> "45,00"). */
export function toInput(decimal: string): string {
  return decimal.replace('.', ',');
}
