# Seed data policy

Only stable, non-sensitive reference data belongs here (for example, built-in payment method labels).

Never seed real customers, supplier contacts, products, stock balances, receipts, payments, or business transactions. Those records must be created through the application and its audited services.

Seed scripts must be idempotent and must never overwrite user-edited values.
