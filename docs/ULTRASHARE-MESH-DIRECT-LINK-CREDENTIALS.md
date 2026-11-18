# UltraShare Mesh — Pair-Specific Direct-Link Credentials

## Purpose

`UltraShareMeshLinkCredential` derives a unique MPC2-compatible 256-bit pairing secret for one pair of authenticated room members. It uses HMAC-SHA-256 with domain separation and binds the derivation to:

- protocol domain and version;
- canonical room ID;
- both canonical stable peer IDs in sorted order;
- the 32-byte room key.

Both endpoints derive the same lowercase 64-character hexadecimal value. Another room, key, or peer pair produces a different credential.

## Security boundary

This helper is cryptographic key derivation only. It does **not**:

- establish a TCP socket or a direct peer link;
- prove that a peer is currently a member of the room;
- distribute the room key;
- authorize an untrusted roster entry;
- replace MPC2's challenge-response, authenticated encryption, or user approval.

The caller must obtain the room key only through an already authenticated and encrypted MPC2 session, validate the current authenticated roster, and derive credentials only for the exact two members being linked. `UltraShareMeshRoomCoordinator.deriveDirectLinkCredential(firstPeerId, secondPeerId)` now enforces that both endpoints are present in the coordinator's current room membership before deriving the value. Never log, persist, display, or send the derived credential through discovery or QR.

## Integration requirements still outstanding

The current service accepts one room pairing secret in its listener lifecycle. A real Mesh runtime still needs a safe per-peer direct-link listener/handshake design, identity-bound session registry, one application-message consumer per MPC2 session, link retry/cleanup, and connection between topology plans and the V2 data plane. Do not mark an edge active merely because a credential was derived.

## Tests added

- direction-independent derivation (A-B equals B-A);
- separation across room IDs, room keys, and peer pairs;
- input length, identity, and self-link validation;
- coordinator-level rejection of peers outside the authenticated room roster;
- malformed credential comparison;
- compatibility with the existing 64-hex MPC2 pairing-secret format.

The tests are committed as source only; they must run in Android CI before this component is considered verified.
