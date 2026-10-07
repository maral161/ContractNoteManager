// Number and date formatting as in the mockup: 1,864.00 / 811,809.28 / -12,109.86

export function formatNumber(value, minDecimals = 2, maxDecimals = minDecimals) {
  if (value === null || value === undefined || value === '') return '';
  const n = Number(value);
  if (Number.isNaN(n)) return String(value);
  return n.toLocaleString('en-US', { minimumFractionDigits: minDecimals, maximumFractionDigits: maxDecimals });
}

export const formatAmount = (v) => formatNumber(v, 2, 2);

/** Quantities: 2 decimals like the mockup, more for funds with fractional units. */
export const formatQuantity = (v, qtyDecimals) => formatNumber(v, 2, Math.max(2, qtyDecimals ?? 0));

export function formatDateTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return `${d.toLocaleDateString('sv-SE')} ${d.toLocaleTimeString('sv-SE', { hour: '2-digit', minute: '2-digit' })}`;
}

export const capitalize = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : '');

export const STATUS_COLORS = {
  NEW: 'default',
  ON_MARKET: 'processing',
  TRADED: 'gold',
  CONFIRMED: 'green',
  ALLOCATED: 'purple',
};

export const NOTE_STATUS = {
  MATCHED: { label: 'Matched', color: 'green', lamp: 'on' },
  PARTIALLY_MATCHED: { label: 'Partially matched', color: 'orange', lamp: 'partial' },
  NO_MATCH: { label: 'No match', color: 'red' },
  EXTRACTION_FAILED: { label: 'Not readable', color: 'default' },
};
