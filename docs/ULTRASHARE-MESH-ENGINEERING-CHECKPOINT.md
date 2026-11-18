# UltraShare Mesh engineering checkpoint

Branch: `feature/zapya-engineering-analysis`

## Scope of this change

This checkpoint adds a deterministic, bounded topology planner for a full mesh. It computes the complete undirected edge set for the room, chooses one deterministic dialer per edge, excludes already-active edges, and chooses only verified private-LAN route candidates.

## Important boundary

This is **Mesh topology groundwork, not a completed runtime Mesh**. It does not yet:
- exchange an authenticated room roster between peers;
- establish the planned MPC2 control sessions automatically;
- confirm every peer-to-peer V2 data endpoint;
- manage link lifecycle/reconnects as a graph;
- route a transfer directly from any source peer to any destination peer;
- prove simultaneous multi-peer transfers on physical Android devices.

The existing app still has a multi-recipient fan-out path and a multi-client V2 receiver, but neither alone proves full-mesh routing.

## Safety properties

- Stable peer IDs are used instead of IP addresses as graph identity.
- Each undirected pair has exactly one deterministic initiator (lexicographically lower stable ID).
- A local device only dials edges for which it is the initiator.
- Self-edges and conflicting duplicate identities are rejected.
- Room size is bounded to 16 participants.
- Only reachable, valid private-LAN endpoints are eligible for a direct link.
- Active edges are excluded from the pending dial plan.

## Required next runtime work

1. Define and version an authenticated room-roster exchange message; never trust UDP discovery as authorization.
2. Carry roster updates over an approved MPC2 session and bind each roster entry to the authenticated stable peer ID.
3. Use the deterministic plan to establish direct MPC2 links, preserving explicit approval or a cryptographically authenticated same-room authorization policy.
4. Verify the V2 data listener on the selected Android Network before marking an edge active.
5. Track edge state independently (connecting, active, stale, failed, closed) and retry with bounded backoff.
6. Add source-to-destination transfer routing that selects the direct source/destination edge, not the room creator.
7. Add integration tests for three and four local peers, simultaneous sends/receives, peer departure, IP change, and resume.
8. Run physical-device tests on at least three Android phones. Emulator/JVM success is not a substitute.

## Release gate

Do not mark Mesh complete, publish a release APK, or merge to `main` until the runtime work, CI checks, and physical-device tests pass.
