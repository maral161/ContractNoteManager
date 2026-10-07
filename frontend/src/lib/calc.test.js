import { describe, expect, it } from 'vitest';
import { postTrade, roundToStep, settlementAmount, splitCommission } from './calc';

describe('edit modal calculations (same results as the backend)', () => {
  it('splits commission like Sharpfin: 1,200 → 1,134 + 66', () => {
    expect(splitCommission(1200, [1761, 103])).toEqual([1134, 66]);
  });

  it('keeps the total when rounding', () => {
    const parts = splitCommission(100, [1, 1, 1]);
    expect(parts.reduce((a, b) => a + b, 0)).toBe(100);
  });

  it('recalculates post-trade figures (ABB, Kattegatt example)', () => {
    expect(postTrade({ preQuantity: 2927, preWeight: 12.55, quantity: 1761, sell: true })).toEqual({
      postQuantity: 1166,
      postWeight: 5,
      orderWeight: -7.55,
    });
  });

  it('shows no post-trade figures without a holding', () => {
    expect(postTrade({ preQuantity: null, preWeight: null, quantity: 5, sell: true }).postQuantity).toBeNull();
  });

  it('settlement amount is negative for buys', () => {
    expect(settlementAmount({ amountOrder: false, sell: false, price: 123.57, quantity: 98 })).toBe(-12109.86);
    expect(settlementAmount({ amountOrder: false, sell: true, price: 435.52, quantity: 1864 })).toBe(811809.28);
  });

  it('rounds quantities to the chosen step', () => {
    expect(roundToStep(1761, 10)).toBe(1760);
    expect(roundToStep(1761, 100)).toBe(1800);
    expect(roundToStep(1761, null)).toBe(1761);
  });
});
