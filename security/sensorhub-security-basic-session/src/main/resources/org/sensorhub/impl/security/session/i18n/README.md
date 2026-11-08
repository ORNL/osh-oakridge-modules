# Session Basic Authentication

The **Session Basic Authentication** module authenticates browser and API requests using an opaque server-side session cookie after verifying user credentials.

## Plain-Language Terms

| Term | Meaning |
| --- | --- |
| **Opaque Session Cookie** | A random, unguessable token stored in the browser cookie after login. Instead of re-sending the password on every request, the browser sends this token and OSCAR looks up the active session on the server. |
| **Idle Timeout vs. Absolute Timeout** | **Idle Timeout** logs a user out after a period of inactivity; **Absolute Timeout** forces a fresh login after a maximum total time even if the user has been active. |
| **SameSite Policy** | Browser rule controlling whether the session cookie is sent on cross-site requests. `Strict` restricts the cookie to the local node; `None` allows federated multi-node connections over HTTPS. |

## Configuration Reference

- **Session cookie name**: Name of the browser cookie holding the session token.
- **Idle timeout (seconds)**: Seconds of inactivity before the session expires.
- **Absolute timeout (seconds)**: Maximum lifetime of a session before re-authentication is required.
- **Secure cookie**: Ensures the cookie is only transmitted over encrypted HTTPS connections. Keep enabled in production.
- **SameSite policy**: Cookie sharing policy (`Strict`, `Lax`, or `None`).
- **Default redirect path**: Local URL path opened after login when no prior destination page was requested.
