// Live calculations of the edit modal. They mirror AllocationMath.java in the backend,
// which recalculates and stores the same values when the order is saved.

const round = (value, decimals) => {
  const f = 10 ** decimals;
  return Math.round((value + Number.EPSILON * Math.sign(value)) * f) / f;
};

/** Settlement amount as Sharpfin shows it: positive for sells, negative for buys. */
export function settlementAmount({ amountOrder, sell, price, quantity }) {
  const gross = amountOrder || price == null ? quantity : round(price * quantity, 2);
  return sell ? gross : -gross;
}

/**
 * Splits the order commission by quantity: whole units for whole commissions, cents otherwise;
 * the rounding remainder goes to the largest allocation so the parts add up.
 */
export function splitCommission(total, quantities) {
  const sum = quantities.reduce((a, b) => a + b, 0);
  if (!total || !sum) return quantities.map(() => 0);
  const decimals = Number.isInteger(total) ? 0 : 2;
  const parts = quantities.map((q) => round((total * q) / sum, decimals));
  let largest = 0;
  quantities.forEach((q, i) => {
    if (q > quantities[largest]) largest = i;
  });
  const remainder = round(total - parts.reduce((a, b) => a + b, 0), decimals);
  parts[largest] = round(parts[largest] + remainder, decimals);
  return parts;
}

/** Post-trade holding and weights from the pre-trade figures and the new quantity. */
export function postTrade({ preQuantity, preWeight, quantity, sell }) {
  if (preQuantity == null || preWeight == null || Number(preQuantity) === 0) {
    return { postQuantity: null, postWeight: null, orderWeight: null };
  }
  const pre = Number(preQuantity);
  const post = sell ? pre - quantity : pre + quantity;
  const postWeight = round((Number(preWeight) * post) / pre, 2);
  return { postQuantity: post, postWeight, orderWeight: round(postWeight - Number(preWeight), 2) };
}

/** Rounds a quantity to the "Quantity rounding" step (None / 1 / 10 / 100). */
export function roundToStep(quantity, step) {
  if (!step) return quantity;
  return Math.round(quantity / step) * step;
}
