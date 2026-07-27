# Daily Usage

This document defines the user-facing financial behavior. Configuration and installation are covered separately in [Configuration](CONFIGURATION.md) and [Local deployment](DEPLOYMENT.md).

## Supported movement types

| Type | Meaning | Required information | Accounting effect |
| --- | --- | --- | --- |
| `expense` | Money spent by the owner | Positive amount and a useful merchant, item, or note | Increases net spending |
| `refund` | Money returned from a purchase | Positive amount; related expense ID when useful | Reduces net spending |
| `loan` | Money another person owes the owner | Positive amount and person | Increases receivables |
| `loan_payment` | Money repaid against a loan | Positive amount and related loan entry ID | Reduces that receivable |

Amounts are never entered as negative values. The selected movement type determines their effect.

## Record through Discord

Use the dedicated expense channel configured by `EXPENSE_DISCORD_CHANNEL_ID`. The skill is bound only to that channel, and Java independently rejects write requests carrying another channel ID.

Send one movement per Discord message:

```text
compra pollo 40
salida comer mc 140.1
gasto gas 321
prestamo Ricardo 3400
devolucion tienda 25
gasto hotel 80 USD
compra farmacia 125 ayer
```

If currency is omitted, `EXPENSE_BASE_CURRENCY` applies. If the date is omitted, the current date in `EXPENSE_TIMEZONE` applies. Relative dates such as `ayer` are interpreted in that timezone.

One message creates at most one draft. Split a message such as `gas 300 y comida 80` into two messages.

## Preview and confirmation

Every new movement follows this state sequence:

```text
Discord message -> pending draft -> preview -> confirm, edit, or cancel
```

The model may create only the pending draft in the first response. It must show the canonical preview returned by Java and wait for another user message.

After reviewing the preview, use a clear instruction:

```text
confirmar
editar el monto a 135
cambiar la moneda a USD
poner categoria Groceries
cancelar
```

An edited draft returns to preview and requires confirmation again. A cancelled draft cannot be confirmed. A confirmed draft creates exactly one ledger entry in the same database transaction as its audit event.

Do not assume that phrases embedded in the original expense text are commands. The original message is treated as untrusted financial data and retained unchanged.

## Expenses and refunds

Examples:

```text
gasto gasolina 350
almuerzo con Ana 95 GTQ
hotel 120 USD el 2026-07-20
me devolvieron 40 de la compra del supermercado
```

A refund is a separate positive `refund` movement, not a negative expense. Link it to the prior expense when the entry ID is known and the relationship is useful; an unlinked refund is also supported. A linked refund must use the expense's currency, and cumulative linked refunds cannot exceed the original expense.

Suggested categories are short English labels such as `Dining`, `Groceries`, `Transport`, `Utilities`, `Health`, `Entertainment`, `Shopping`, `Travel`, and `Other`. Categories are descriptive and do not change accounting behavior.

## Loans and repayments

A loan represents money owed to the owner:

```text
prestamo Ricardo 3400
```

The draft must contain the person's name. After confirmation, the dashboard and summary tools show the loan as an open receivable.

A repayment must reference the original confirmed loan entry. A safe conversational sequence is:

```text
mostrar los prestamos abiertos de Ricardo
Ricardo pago 500 del prestamo <loan-entry-id>
confirmar
```

If the person has one unambiguous open loan, Hermes may identify it from the ledger. If multiple loans could match, it must ask which entry to use before creating the repayment draft. Repayments cannot exceed the outstanding balance and must use the loan's currency.

## Multiple currencies

Specify an ISO 4217 currency code when it differs from the configured default:

```text
gasto taxi 18 USD
hotel 90 EUR
```

The ledger stores and summarizes each currency independently. It does not fetch exchange rates, estimate conversions, or combine currencies into one total.

Ask for one currency explicitly when needed:

```text
mostrar gastos en USD de este mes
cuanto gaste en GTQ esta semana
```

## Queries through Discord

Examples of read-only requests:

```text
mostrar mis ultimos 20 movimientos
cuanto gaste este mes
resumen entre 2026-07-01 y 2026-07-31
quienes me deben y cuanto
mostrar prestamos abiertos
hay borradores pendientes
mostrar devoluciones en USD
estado del ultimo backup
verificar la salud del sistema
```

Read operations do not require a preview or confirmation. Results group monetary values by currency.

## Dashboard workflow

Discover the private dashboard URL with:

```bash
tailscale serve status
```

Open the reported HTTPS URL from a device connected to the same tailnet. Sign in with the value of `EXPENSE_WEB_ACCESS_TOKEN` from `.env`.

The dashboard home page shows:

- net spending totals grouped by currency;
- open receivables;
- the latest backup state;
- up to 50 pending drafts; and
- up to 50 recent confirmed entries with their original messages.

Pending drafts have `Edit`, `Confirm`, and `Cancel` actions. Editing permits changes to type, amount, currency, date, merchant, category, person, note, and related entry ID. Saving an edit preserves the pending state so it can be reviewed before confirmation.

Confirmed entries are read-only in the current interface. Correct an unconfirmed mistake by editing its draft; do not confirm a preview that is wrong.

## Duplicate and ambiguous messages

The source Discord channel ID and message ID form an idempotency key. Retrying delivery of the same Discord message cannot create another draft.

Hermes must ask a focused question instead of inventing data when:

- the amount is missing or ambiguous;
- the currency cannot be inferred safely;
- a loan has no person;
- a loan payment has no identifiable original loan; or
- one message appears to contain multiple movements.

## Current functional limits

- The deployment supports one owner workflow, although multiple Discord user IDs can be allowlisted.
- One Discord message represents one movement.
- Confirmed entries are not edited or deleted through the provided interfaces.
- Currency conversion is not implemented.
- Backups remain local until an off-site backend is added.
