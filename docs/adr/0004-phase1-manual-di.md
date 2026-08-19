# ADR 0004: Keep Phase 1 dependency injection explicit

## Status
Accepted for Phase 1.

## Decision
Use a small application-level `AppContainer` with constructor injection instead of introducing Hilt/KSP before the camera capability boundary is stable.

## Rationale
The capability scanner has one Android implementation and one consumer today. Manual DI keeps the dependency graph obvious, avoids annotation-processing build complexity, and remains easy to replace with Hilt later without changing the domain interfaces.

## Consequences
- No service locator calls inside feature code.
- ViewModels receive interfaces through factories.
- Revisit DI framework choice when Phase 2 adds multiple camera engines and repositories.
