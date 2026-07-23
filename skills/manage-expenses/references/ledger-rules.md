# Ledger Rules

## Movement types

| Type | Meaning | Required details |
| --- | --- | --- |
| `expense` | Money spent | Amount and a useful merchant, item, or note |
| `refund` | Money returned from a prior purchase | Amount; related expense ID when known |
| `loan` | Money another person owes the owner | Amount and person |
| `loan_payment` | Money repaid against a receivable | Amount and related loan ID |

Amounts are positive decimal strings. The movement type determines their accounting effect.

## Interpretation examples

- `compra pollo 40` → expense, amount `40`, merchant/item `pollo`.
- `salida comer mc 140.1` → expense, amount `140.1`, merchant `mc`, category `Dining`.
- `gasto gas 321` → expense, amount `321`, merchant/item `gas`, category `Transport`.
- `prestamo ricardo 3400` → loan, amount `3400`, person `Ricardo`.
- `me devolvieron 25 de la compra` → refund; ask for the related entry only if linking is needed.
- `ricardo pago 200 del prestamo` → loan payment; find or ask for the intended open loan before drafting.

## Normalization

- Keep merchant, person, and note close to the user's wording.
- Use short English category names: `Dining`, `Groceries`, `Transport`, `Utilities`, `Health`, `Entertainment`, `Shopping`, `Travel`, or `Other`.
- Do not convert currencies. Omitted currency means the configured base currency, normally GTQ.
- Relative dates are interpreted in the configured timezone, normally `America/Guatemala`.
- One Discord message creates at most one draft in the initial release. Ask the user to split messages containing multiple movements.
