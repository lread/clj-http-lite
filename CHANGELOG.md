### unreleased

- Offer a new client ns: `clj-http-lite.client`
  - Unlike its `clj-http.lite.client` predecessor, it doesn't use the Slingshot library, using vanilla `throw`/`ex-info` instead.
- Make the `slingshot` artifact dependency provided
  - If you intend to use the old `clj-http.lite.client` (or some transitive dep is using it), please add `[slingshot "0.12.2"]` expicitly in your project.
- Support self-signed certificates via `insecure?` option

### 0.4.3

- **Feature:** Parse link headers from response and put them under `:links` ([#1](https://github.com/martinklepsch/clj-http-lite/pull/1))

### 0.4.2

- Add type hints for GraalVM ([#2](https://github.com/martinklepsch/clj-http-lite/pull/2))

### 0.4.0

- **Feature:** Java 9/10 Compatibility
