# Device Compatibility

Physical-device validation has started. Keep raw Camera2 routes, resolved user lenses, and successful session routes separate.

| Device | Android | Client identity | Raw Camera2 graph | Resolved/useful result | Session result | Notes |
|---|---:|---|---|---|---|---|
| Xiaomi POCO M2 Pro | API 36 | `com.omnicam.app` | 2 IDs: rear `0`, front `1`; 0 logical groups | rear main + front only | discovery only | Normal app identity is filtered by the tested ROM/HAL. |
| Xiaomi POCO M2 Pro | API 36 | experimental `org.codeaurora.snapcam` | 8 total IDs; 6 raw rear routes; logical `61` has physical `0`,`20` | 4 useful rear routes: `21`, `22`, `20`, `0`; duplicate/aggregate routes hidden | `21`,`22`,`20`,`0` direct preview work; `100`,`61` fail as independent direct devices | Confirms package-sensitive auxiliary exposure on this ROM. `61` is infrastructure/logical, not another physical lens. `100` is treated as a vendor alias/alternate route. |

## Resolver rules

- Do not show every raw Camera2 ID as a physical camera.
- Logical aggregate IDs are hidden from the normal lens bar.
- Strong optical-metadata duplicates are collapsed as vendor aliases.
- Distinct directly listed photographic devices are useful candidates.
- Physical members disclosed behind a logical camera are useful candidates and use physical-via-logical routing rather than being opened blindly.
- Session success remains the final authority; enumeration is not proof of usability.
- Never infer camera role from a fixed numeric ID table.
- User enable/disable/order is persisted separately from hardware capability resolution.

## Coverage status

The useful-camera resolver is designed generically, but Snapdragon-wide compatibility is **not yet validated**. More Qualcomm devices from Xiaomi, OnePlus, Motorola, Samsung, Nothing, and other OEM/ROM combinations are needed before making broad compatibility claims.
