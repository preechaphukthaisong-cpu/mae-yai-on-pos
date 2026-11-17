# Frontend foundation

Framework-specific source code will be added after the app framework is confirmed.

## Modules
- Authentication: staff sign-in, session expiry and permission-aware navigation.
- Sales: product search, barcode scan, cart, discounts and checkout.
- Checkout: cash, transfer, PromptPay QR, card and split tender.
- Receipt: print, authorized reprint, email and LINE delivery.
- Cash register: open/close shift, float, cash-in/out and reconciliation.
- Catalog: products, categories, images, barcodes, variants and modifiers.
- Inventory: stock view, receiving, count and adjustments.
- CRM: customer history, membership and loyalty.
- Staff: directory, roles, attendance and sales attribution.
- Promotions: coupons and scheduled offers.
- Reports: sales, product performance, payments, stock and profit/loss.

## Rules
- UI never executes SQL or mutates stock directly.
- UI calls shared feature services; all authoritative totals are validated by domain services.
- Show offline/pending-sync status clearly.
- Enforce permissions for voids, discounts, refunds, reprints and cash adjustments.
- Barcode generation must not alter inventory.
