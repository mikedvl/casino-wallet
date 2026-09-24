# Assumptions and Implementation Notes

The following assumptions and implementation decisions apply to the delivered Casino Wallet implementation.

- One seeded demo player is used. Production authentication is outside the assignment scope.

- The first qualifying completed deposit of at least EUR 20.00 receives the one-time welcome bonus, capped at EUR 100.00.

- The full stake counts toward wagering while a bonus is `ACTIVE`. The EUR 5.00 stake limit applies to every bet while the bonus is active, including fully real-funded bets.

- A game round is one synchronous atomic operation. `totalWin` is deterministic demo input representing total payout. Win allocation rounds the real part once with `HALF_UP`; the bonus part receives the exact remainder.

- Callback HMAC covers the exact raw HTTP body. `depositId` identifies the deposit. Idempotency is enforced durably in PostgreSQL using state, row locks and constraints.

- Bonus expiration is lazy and uses an injectable `Clock`. It is resolved before wallet summary, valid pending-deposit completion and play-round execution. Creating a pending deposit does not lock the wallet or trigger expiration.

- Completed wagering takes priority over expiration. A completing round settles its win and advances progress before converting the remaining bonus; terminal statuses cannot change. If expiration is resolved before a business rejection, that resolution is committed, while technical failures roll back the whole operation.

- The assignment-only demo completion endpoint uses the stored deposit amount and the same core transaction as the signed callback. It exposes no provider secret to the browser and can be disabled with `demo.enabled=false` outside the demo.

- Zero-value ledger entries are not created.

- PostgreSQL is the only source of truth for financial state. Wallet changes, ledger entries and related state changes are committed atomically. Redis and in-memory caches are not used for financial consistency or idempotency.

- The observability baseline uses Spring Boot Actuator, Micrometer, `X-Request-ID`, stdout/stderr logging, Nginx logs and Docker Compose healthchecks. A separate monitoring stack is outside scope.

- PostgreSQL, backend and frontend are containerized for reproducible review. Backend and frontend are normally run locally during development for faster feedback.

- Angular 17 is required by the assignment and remains pinned to 17.3.12. Known audit findings are documented; forced major-version upgrades or unsafe dependency overrides are intentionally not applied.