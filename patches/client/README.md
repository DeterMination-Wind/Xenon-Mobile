# Xenon Mobile client overlay

The tag workflow applies the deterministic overlay in
`scripts/xenon-mobile/apply-clone-overlay.py` after checking out the commit in
`game-source-lock.json`. The generated `git diff --binary` is uploaded with
each clone artifact as its patch-series asset. This keeps source, overlay, and
the exact build output auditable without carrying a fork of the upstream game.

The overlay intentionally changes only Android package identity, release
metadata, arm64 ABI selection, and Xenon clone metadata. Mindustry gameplay
and its internal package names remain upstream-owned.
