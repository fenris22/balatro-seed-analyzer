# Balatro Seed Analyzer

A fast Balatro seed finder. Describe the run you want ("a Negative Blueprint in an ante 1–2 Buffoon pack, Perkeo from a Soul by ante 3") and it searches millions to billions of seeds for the ones that have it. It ranks them by how early and how well they match.

It reproduces Balatro's random number generation exactly, and runs the search on your GPU through OpenCL. It is written in Kotlin and built for double-precision (FP64) heavy hardware. On data-center GPUs like AMD's MI300X it reaches billions of seeds per second, depending on the conditions. There are about 2.3 trillion possible seeds, so a well-chosen search on rented hardware can cover the whole seed pool in about an hour.

- **Web interface:** set up conditions, watch progress, speed, time left and how many seeds match, then click a result to see the whole seed ante by ante.
- **Runs anywhere:** downloads include their own Java runtime, so there is nothing to install. It falls back to the CPU when there is no usable GPU, with identical results.
- **Checked against the game:** the GPU results are cross-checked against the CPU on every run, and the generator has been confirmed in game.

---

## Download and run

Get the latest version from the [Releases page](../../releases).

| System | Download | Run |
|---|---|---|
| **Windows** | `seedfinder-<version>-windows-x64.zip` | Unzip it, then double-click `seedfinder.bat` |
| **Linux** | `seedfinder-<version>-linux-x64.run` | `sh seedfinder-<version>-linux-x64.run` |

The finder opens in your browser at `http://localhost:7777`. Keep the console window open while you use it; closing it (or the page's **Quit** button) stops the program.

> **Windows shows a security warning?** The download isn't code-signed yet. Choose *Run* (or *More info → Run anyway*). You can avoid the warning entirely by right-clicking the zip, choosing *Properties*, ticking *Unblock*, then extracting.

The Linux `.run` file unpacks itself into `~/.cache/seedfinder` the first time, and starts instantly after that. The `.tar.gz` download has the same thing already unpacked (`seedfinder/bin/seedfinder`).

---

## Using the web page

1. **Add conditions.** Each one is a card, or a list of cards where any of them counts, plus where and when it must show up:
   - **From:** Shop, Pack, Soul (the legendary a Soul gives), Tag, Voucher or Boss.
   - **Antes:** the window it has to appear in.
   - **Slots:** how early it must appear. For the shop, 1 is the first card (rerolls continue the count). For packs, it's the card's position; for Souls, which Soul of the run; for tags, 1 is the small blind and 2 the big blind.
   - **Edition:** any, a specific one, or "no edition".
   - **How many:** e.g. "3× Blueprint or Brainstorm".
2. **Mark the must-haves as Required.** Seeds without them are dropped as soon as their window is over. This is by far the biggest speedup. Conditions that aren't required only add to the score.
3. **Estimate how common** samples random seeds for a few seconds, and shows how rare each condition is and roughly how many seeds in your range should match them all.
4. **Start search.** You'll see the speed, time left, and a live count of seeds meeting your required conditions. Results fill in as they're found; click one to see its bosses, vouchers, tags, shop and packs, with your cards highlighted.

**Scoring:** inside a condition's window, matches closer to its best ante and slot score higher. The *Scoring* section on each condition sets those targets and their weights. Only the best seeds are kept (200 by default).

**Stopping and resuming:** Stop saves what's been found and tells you the seed index to continue from. Results are also written to `results.txt` next to the program as the search runs.

**Save to file / Open file** stores your conditions and settings as JSON, for sharing or for running on a server.

---

## Running on a server or cloud GPU

To search without the web page, save your conditions from the page (*Save to file*), copy `conditions.json` over, and run:

```
sh seedfinder-<version>-linux-x64.run --conditions conditions.json --chunk 64m
```

It prints progress, saves `results.txt` as it goes, and exits when finished. Flags override the settings stored in the file.

To use the web page on a remote machine instead, either:

- **tunnel it (private):** `ssh -L 7777:localhost:7777 user@server`, then open `http://localhost:7777` on your own computer; or
- **expose it:** run with `--host 0.0.0.0` and open the port in your provider's settings. On RunPod, expose 7777 as an HTTP port and open `https://<pod-id>-7777.proxy.runpod.net`. **There is no password:** anyone with the address can use it.

### Command-line options

Run with `--help` for the full list. Counts accept `k`, `m`, `b` and `t` (`312m`, `1.5b`).

| Option | What it does |
|---|---|
| `--conditions FILE` | Search the conditions in FILE without the web page, then exit |
| `--start-index N`, `--end-index N` | Which seeds to search (default 0 to 312m) |
| `--max-results N` | How many top seeds to keep (default 200) |
| `--disable-gpu` | Search on the CPU only |
| `--chunk N` | Seeds per GPU launch (default 4m; 64m suits data-center GPUs, lower it on a GPU that drives your screen) |
| `--global-size N`, `--local-size N` | GPU work sizes (defaults 4096 and 64) |
| `--checkpoint N` | Save results every N seeds (default: every tenth of the range) |
| `--examine SEED` | Print one seed's antes 1–8 in full and exit |
| `--port N`, `--host ADDR`, `--no-browser` | Web page address options |

---

## GPU support

Any GPU with OpenCL and double-precision (`cl_khr_fp64`) support works: AMD, NVIDIA and Intel. The search is almost entirely FP64 maths, so speed follows a card's **FP64** throughput rather than its gaming performance:

- **Data-center cards** (AMD Instinct MI300X and up, NVIDIA A100/H100) are in a different league: billions of seeds per second.
- **Consumer and workstation cards** run FP64 at a small fraction of their normal speed, so expect millions to tens of millions per second. That's still well ahead of a CPU.

Every search runs on the GPU except one needing playing cards from Standard packs, which falls back to the CPU. The page's *Checks* box tells you which one a search will use.

### GPU not detected?

The page's header shows the GPUs found, or "No GPU: CPU only" with the reason when you hover over it.

- **Windows:** install the normal graphics driver from AMD, NVIDIA or Intel. It includes OpenCL.
- **Linux, AMD:** install ROCm (or at least its OpenCL runtime).
- **Linux, NVIDIA desktop:** NVIDIA's own driver includes OpenCL. If the card still isn't found, install the OpenCL loader: `sudo apt install ocl-icd-libopencl1`. The open-source *nouveau* driver has no usable OpenCL.
- **Cloud containers with NVIDIA GPUs** (RunPod and similar): these usually provide CUDA but leave out part of the OpenCL setup. Check what's there:

  ```
  ldconfig -p | grep -i opencl
  ls /etc/OpenCL/vendors/ 2>/dev/null
  ```

  If the first command lists `libnvidia-opencl.so.1`, NVIDIA's OpenCL driver is present and only the loader and its pointer file are missing. Add them:

  ```
  apt-get update && apt-get install -y ocl-icd-libopencl1 clinfo
  mkdir -p /etc/OpenCL/vendors
  echo "libnvidia-opencl.so.1" > /etc/OpenCL/vendors/nvidia.icd
  clinfo -l
  ```

  `clinfo -l` should now list "NVIDIA CUDA" and your GPU. Restart the finder; the log should say `platform NVIDIA CUDA: 1 usable GPU(s)`. If `libnvidia-opencl.so.1` isn't there at all, the container was started without NVIDIA's compute libraries: pick a standard CUDA or PyTorch image instead. This setup is lost when the container resets, so add it to the pod's start command if you use it often.

---

## What it assumes about your run

Seeds are generated for **White Stake, Red Deck**, and the finder models a straightforward way of playing:

- **Vouchers** are bought as soon as they appear. The exceptions are the ones you tick under *Vouchers you never buy*: Planet Merchant, Magic Trick and Tarot Merchant by default. Owned vouchers change the shop odds and editions, and affect Omen Globe and Telescope. The ante's first shop cards are dealt before its voucher can be bought.
- **Shop and pack cards** can repeat, as if you held Showman, because what's on screen depends on how you play.
- **Souls** are the exception: every legendary a Soul gives is assumed kept, so a later Soul rerolls legendaries you already have. A seed where a sixth Soul appears after all five legendaries are taken is set aside as unresolvable.
- **Telescope's** forced first Celestial card depends on your most-played hand, so it never counts as a match.

---

## Building from source

Requirements: JDK 25 and the included Gradle wrapper.

```
./gradlew release         # everything for your OS, into build/release/
./gradlew bundleRun       # Linux: the single self-extracting .run file
./gradlew shadowJar       # just the jar (needs Java 25 installed to run)
```

The runtime bundled with a build matches the OS it was built on, so build the Windows files on Windows and the Linux files on Linux.

**Releases** are built automatically by GitHub Actions: push a tag (`git tag v1.3 && git push origin v1.3`) and the Linux and Windows files are built and attached to a new release.

### Project layout

| File | What's in it |
|---|---|
| `search.cl` | The OpenCL kernel: generation, matching and scoring on the GPU |
| `ClSearch.kt` | GPU host code: stream tables, kernel build, launches, CPU cross-checks |
| `Generator.kt`, `Rng.kt` | The CPU seed generator and Balatro's RNG, the reference the GPU is checked against |
| `Conditions.kt`, `Planner.kt` | Conditions, scoring, and the quick pre-check passes run before a full scan |
| `SearchRun.kt`, `Main.kt`, `Cli.kt` | Running a search, command-line options |
| `WebServer.kt`, `web/index.html` | The web interface (the JDK's built-in HTTP server, no extra libraries) |
| `Items.kt` | Card pools, generated from [Immolate](https://github.com/SpectralPack/Immolate) |

---

## Credits

Card pools and much of the generation logic are based on [Immolate](https://github.com/SpectralPack/Immolate) by SpectralPack. Balatro is made by LocalThunk and published by Playstack. This is an unofficial fan project.
