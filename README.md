# Forge Nova

A GPU (WebGL2) front end and engine speed-ups for [Forge](https://github.com/Card-Forge/forge), built to stay
smooth on huge Commander boards, plus online play where friends join from their browser.

This repository holds only Nova's own files. They live inside a Forge install folder, next to Forge's jars;
Forge itself is not included.

## Install

1. Install Forge's desktop version (Java 17+). Nova was built against `forge-gui-desktop-2.0.15-SNAPSHOT`
   (Forge build of 2026-09-22), and its engine patches are tied to that build (see *Development* in nova/README.md).
2. Copy `forge-nova.cmd` and the `nova` folder from this repository into the Forge folder.
3. Double-click `forge-nova.cmd`. The first start compiles Nova, which needs a JDK 17+ (`javac`).

Features, online play, development and how the engine patches were verified: [nova/README.md](nova/README.md).
