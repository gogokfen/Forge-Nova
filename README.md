<p align="center"><img src="nova/client/brand/icon-512.png" width="160" alt="Forge Nova"></p>

# Forge Nova

A GPU (WebGL2) front end and engine speed-ups for [Forge](https://github.com/Card-Forge/forge), built to stay
smooth on huge Commander boards, plus online play where friends join from their browser.

This repository holds only Nova's own files. They live inside a Forge install folder, next to Forge's jars;
Forge itself is not included.

## Install

**Download [ForgeNova.exe](https://github.com/gogokfen/Forge-Nova/releases/latest/download/ForgeNova.exe) and run it.**
That's all: it downloads Java, the Forge build Nova is made for and Nova itself, keeps them up to date, and puts
Forge Nova on your Desktop and in the Start menu. (Windows may warn about an unrecognised app the first time:
*More info → Run anyway*.)

Into an existing Forge folder instead: Forge's desktop build `forge-gui-desktop-2.0.15-SNAPSHOT` (2026-09-22; the
engine patches are tied to it) and Java 21+. Copy `forge-nova.cmd` and the `nova` folder into the Forge folder and
double-click `forge-nova.cmd`; the first start compiles Nova, which needs a JDK (`javac`).

Features, online play, development and how the engine patches were verified: [nova/README.md](nova/README.md).
