# HoneyFlix

HoneyFlix is a personalised build of [Nuvio TV](https://github.com/NuvioMedia/NuvioTV) (GPL v3), part of the HoneyStream family alongside HoneyLive.

- Branch `honey` holds all HoneyFlix changes; `dev` mirrors Nuvio. Merge `dev` into `honey` to take Nuvio's updates.
- Edition (product flavor) `honey`: the complete Nuvio app, app ID `com.honeystream.flix`, updates from this repo's releases.
- HoneyFlix additions live in `app/src/honey` (branding) and `app/src/main/java/com/honeystream` (HoneyFlix code); edits to Nuvio files are kept small and marked "HoneyFlix".
- Builds: `.github/workflows/honey.yml` on every push to `honey`. Needs secrets `HONEYFLIX_KEYSTORE_BASE64` and `HONEYFLIX_KEYSTORE_PASSWORD`.

Licensed under GPL v3, like Nuvio. Nuvio's copyright and license notices are kept.
