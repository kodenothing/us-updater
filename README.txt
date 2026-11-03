# Us App 2.4.3 — Spark-compatible shared road routing

This patch keeps Firebase Spark and does not use Cloud Functions to calculate routes.

## How routing works

1. Each phone continues publishing its own latest shared location to:
   `couples/{coupleId}/locations/{uid}`.
2. After a successful location write, that phone checks whether a route refresh is needed.
3. A Firestore transaction elects one active calculator by granting or renewing a lease at:
   `couples/{coupleId}/map/routeControl`.
4. The elected phone keeps the lease renewed while it continues publishing its own location. The other phone only checks the lease and does not make OSRM requests while the elected phone is active.
5. The lease holder calls the public OSRM driving endpoint from Android and publishes the encoded `polyline6` geometry to:
   `couples/{coupleId}/map/roadRoute`.
6. Both phones observe the same route document and render the same geometry in MapLibre.
7. If the lease holder closes or loses connectivity, the 90-second lease expires. The next phone that successfully publishes a location can take over.

The route document is client-written only by the currently elected lease holder. Firestore rules check couple membership, lease ownership/expiry, allowed fields, coordinate ranges, geometry size, and server timestamp.

## Routing controls

- Location freshness required: 90 seconds.
- Recalculate after either endpoint moves about 60 meters from the last routed endpoint.
- Minimum interval for successful-route refresh: 45 seconds.
- Retry after a route failure: 2 minutes.
- Lease duration: 90 seconds, renewed by the elected phone during its periodic location publications.
- Route coordination check: throttled locally to once every 45 seconds per phone.
- OSRM connect/read timeout: 15 seconds.
- No straight-line connector is drawn when road geometry is unavailable.

These are safeguards, not guaranteed refresh deadlines. The route changes when a new location write causes a phone to check the shared state. The active route can remain unchanged while movement stays under the threshold.

## Firebase Spark deployment

From the Firebase project root, deploy **only Firestore rules**:

```powershell
firebase deploy --only firestore:rules
```

Do not run `firebase deploy --only functions` for this routing update. The route calculator is Android-side and does not require Cloud Functions, Cloud Build, Artifact Registry, or a Blaze upgrade.

The `functions/index.js` included in this source bundle retains the existing `notifyActivityCreated` notification function but contains no route trigger. That notification function is unrelated to shared road routing and is not deployed by the command above. Cloud Functions deployment itself remains unavailable on Spark.

## Android files

The source bundle preserves the project Kotlin files under:
`app/src/main/java/com/maenalyn/couples/`

Key changes:
- `LocationSharing.kt`: observes the route document, decodes `polyline6`, elects a route calculator using Firestore transactions, calls OSRM from the active phone, and publishes the shared route.
- `MapScreen.kt`: displays the Firestore route and does not draw a fake straight connector while waiting or after a route failure.
- `MainVm.kt` and `Screens.kt`: retain the shared route listener and pass the route state to the map tab.
- `firestore.rules`: allow members to read shared routes, allow only the current lease holder to publish, and keep route control deletion disabled.

No new Android dependency is required. The existing Firestore Kotlin coroutine `Task.await()` support is already used by the project.

## Test on both phones

1. Deploy the updated Firestore rules.
2. Install the same updated Android build on both phones.
3. Sign in to the same couple account on both phones.
4. Enable location sharing on both phones and confirm that both `locations/{uid}` documents are updating.
5. Open the map on both phones. The first phone to claim the route lease calculates the road geometry; both phones then receive the same route document.
6. Move one phone more than approximately 60 meters and wait for a subsequent location publication to trigger a refresh.
7. If OSRM is unreachable or returns no route, both phones show the route as unavailable and no straight road connector is drawn.

For Android Studio Logcat, filter on `SharedRoadRoute`. Expected messages include `Shared road route published` or `Shared road route published as unavailable`.

## Spark limitations and behavior

- One of the phones must be online and running code that publishes locations for new routes to be calculated. No server process runs while both clients are inactive.
- If the current lease holder disappears, another phone can take over after the 90-second lease expires and it publishes a new location.
- Both phones still need internet access for Firestore synchronization. The elected phone also needs internet access to call OSRM.
- The public OSRM demo service is best-effort and has no uptime guarantee. This update removes duplicate phone requests; it does not make OSRM a guaranteed production service.
- Firestore reads and writes count toward the project's Spark quota. Route writes are throttled, but location publication already uses Firestore writes.
- Route geometry is shared, but location markers continue updating independently from the shared location documents.

## Validation status

The uploaded project archive contains Kotlin sources but not the full Gradle project/wrapper or Firebase CLI project configuration. The patch therefore has not been compiled in Android Studio or deployed to the user's Firebase project. Run the Firestore rules deployment command above, then build and test on both physical devices.
