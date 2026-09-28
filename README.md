# Balatro Seed Analyzer

Find Balatro seeds with exactly the run you want. Describe it ("a Negative Blueprint from a
Buffoon pack by ante 2, Perkeo from a Soul by ante 3") and the finder searches millions to
trillions of seeds on your graphics card, then ranks the matches by how early and how well
they deliver.

- **Nothing to install:** each download includes everything it needs. Unzip and run.
- **Point and click:** a web page opens in your browser. Set up what you're looking for, press
  Start, click a result to see the seed ante by ante.
- **Exact:** it reproduces Balatro's random number generator bit for bit, and the GPU's
  results are double-checked against a separate CPU version.
- **Fast:** about 130 million seeds per second on an RX 9070 XT and 700 million on an NVIDIA
  A100 for a typical search, and every GPU setting tunes itself.

---

## Quick start

1. Download the latest version from the [Releases page](../../releases):

   | Your computer | Download | To run it |
   |---|---|---|
   | **Windows** | `seedfinder-<version>-windows-x64.zip` | Unzip, then double-click `seedfinder.bat` |
   | **Linux** | `seedfinder-<version>-linux-x64.run` | `sh seedfinder-<version>-linux-x64.run` |

2. Your browser opens the finder at `http://localhost:7777`. Leave the console window open
   while you use it; closing it, or pressing **Quit** on the page, stops the program.

3. On the page:
   1. **Add a condition** for each thing you want: the card (or several, where any of them
      counts), where it should come from (shop, pack, Soul, tag, voucher, boss), and which
      antes it has to appear in.
   2. **Tick "Required"** on the must-haves. Seeds that can't have them are thrown away early,
      which is what makes searches fast.
   3. Press **Start search**. Results appear as they're found; click one to see it in full.

> **Windows warning ("Windows protected your PC")?** The download isn't code-signed yet.
> Click *More info → Run anyway*. To avoid the warning, right-click the zip, choose
> *Properties*, tick *Unblock*, then unzip.

That's all you need. Everything below is for when you want more.

---

## Using the web page

**Conditions.** Each condition is one card, or a list where any card counts, plus:

- **From:** Shop, Pack, Soul (the legendary a Soul gives), Tag, Voucher or Boss.
- **Antes:** the window it must appear in.
- **Slots:** how early it must appear. In the shop, 1 is the first card and rerolls keep
  counting; in packs, the card's position; for Souls, which Soul of the run; for tags, 1 is
  the Small Blind and 2 the Big Blind.
- **Edition:** any, one specific edition, or "no edition".
- **How many:** for example "3× Blueprint or Brainstorm".

**Estimate how common** samples random seeds for a few seconds and tells you how rare each
condition is, and roughly how many seeds in your range should match them all. Use it to see
whether a search is realistic before you run it.

**Scoring.** Within a condition's window, matches closer to its best ante and slot score
higher; the *Scoring* part of each condition sets those targets and weights. The best 200
seeds are kept (change it with *Max results*).

**Stop and resume.** Stop keeps what has been found and shows the seed index to continue
from. Results are also written to `results.txt` next to the program as the search runs.

**Look up one seed.** Type any seed into the lookup box to see its bosses, vouchers, tags,
shop and packs, with your cards highlighted.

**Save to file / Open file** stores your conditions as JSON, for sharing or for running on
a server.

---

## Running on a server or a cloud GPU

Rented data-center GPUs are much faster at this than gaming cards (see
[Speed](#speed)), so it's worth knowing how to run there.

**Without the web page.** Save your conditions from the page (*Save to file*), copy
`conditions.json` to the server, and run:

```
sh seedfinder-<version>-linux-x64.run --conditions conditions.json
```

It prints progress, writes `results.txt` as it goes, and exits when done. Any flag you add
overrides the file.

**With the web page on a remote machine,** either:

- **tunnel it (private):** `ssh -L 7777:localhost:7777 user@server`, then open
  `http://localhost:7777` on your own computer; or
- **expose it:** start with `--host 0.0.0.0 --no-browser` and open port 7777 in your provider's
  settings. On RunPod, expose 7777 as an HTTP port and open
  `https://<pod-id>-7777.proxy.runpod.net`. **There is no password**: anyone who has the
  address can use it.

---

## GPU not detected?

The page header lists the GPUs found, or says "No GPU: CPU only" (hover over it for the
reason). Without a GPU the finder still works, with identical results, just slower.

- **Windows:** install the normal graphics driver from AMD, NVIDIA or Intel. It includes OpenCL.
- **Linux, AMD:** install ROCm, or at least its OpenCL runtime.
- **Linux, NVIDIA desktop:** NVIDIA's own driver includes OpenCL. If the card still isn't
  found, install the OpenCL loader: `sudo apt install ocl-icd-libopencl1`. The open-source
  *nouveau* driver has no usable OpenCL.
- **Cloud containers with NVIDIA GPUs** (RunPod and similar) usually have CUDA but only part
  of the OpenCL setup. Check:

  ```
  ldconfig -p | grep -i opencl
  ls /etc/OpenCL/vendors/ 2>/dev/null
  ```

  If the first command lists `libnvidia-opencl.so.1`, the driver is there and only the
  loader and its pointer file are missing. Add them:

  ```
  apt-get update && apt-get install -y ocl-icd-libopencl1 clinfo
  mkdir -p /etc/OpenCL/vendors
  echo "libnvidia-opencl.so.1" > /etc/OpenCL/vendors/nvidia.icd
  clinfo -l
  ```

  `clinfo -l` should now list "NVIDIA CUDA" and your GPU, and the finder's log should say
  `platform NVIDIA CUDA: 1 usable GPU(s)`. If `libnvidia-opencl.so.1` isn't there at all, the
  container has no NVIDIA compute libraries; pick a standard CUDA or PyTorch image instead.
  This setup is lost when the container resets, so put it in the pod's start command if you
  use it often.

---

## What it assumes about your run

Seeds are generated for **White Stake, Red Deck**, played in a straightforward way:

- **Vouchers** are bought as soon as they appear, except the ones you tick under *Vouchers
  you never buy* (Planet Merchant, Magic Trick and Tarot Merchant by default). Owned vouchers
  change the shop odds and editions, and affect Omen Globe and Telescope. An ante's first
  shop cards are dealt before its voucher can be bought.
- **Shop and pack cards can repeat**, as if you held Showman, since what's on screen depends
  on how you play.
- **Souls** are the exception: every legendary a Soul gives is assumed kept, so a later Soul
  rerolls legendaries you already have. A sixth Soul after all five legendaries are taken
  can't be resolved, and the finder reports how many such seeds it set aside.
- **Telescope's** forced first Celestial card depends on your most-played hand, so it never
  counts as a match.

---

# The details

Everything from here on is for the curious: how fast it is, why the results can be trusted,
how the GPU search works, and how to build it.

## Speed

Speed depends heavily on the search: a rare required condition early in the run throws most
seeds away within a few dozen random draws, while loose conditions make every seed go deep.
Measured with every setting on automatic, on 8-character seeds:

| Search (what's required) | RX 9070 XT | A100 SXM 80 GB |
|---|---|---|
| Chicot and Perkeo from Souls, Negative Blueprint/Brainstorm by ante 2 | 136M/s | 701M/s |
| A common joker in ante 1's first two pack slots, then Blueprint by ante 3 | 209M/s | 1,078M/s |
| Brainstorm by ante 2, two of Blueprint/Showman by ante 5 | 130M/s | 596M/s |
| Blueprint from a pack by ante 4, Perkeo from a Soul by ante 6 | 78M/s | 320M/s |
| Perkeo and Chicot from Souls by antes 3 and 4 | 28M/s | 110M/s |
| 3× The Fool and 2× The Hermit from packs by ante 3 | 28M/s | 128M/s |
| 3× Pluto and a Black Hole from packs | 20M/s | 67M/s |
| Ectoplasm or Hex, plus The Soul, from packs | 18M/s | 62M/s |

There are 2,318,107,019,760 seeds of 1 to 8 characters, so the first search above would
cover the entire seed pool in about 55 minutes on a single A100.

**Which GPU to use.** The search is almost entirely double-precision (FP64) arithmetic,
because Balatro's random numbers live in 64-bit floats and have to be reproduced exactly.
Data-center cards (NVIDIA A100/H100/B200, AMD Instinct MI200/MI300 and up) have fast FP64
and are several times faster than any gaming card. Gaming and workstation cards run FP64 at a
small fraction of their normal speed, but still beat a CPU by a wide margin. Avoid cards that
dropped FP64 entirely (NVIDIA's B300, for example).

## Exactness

The finder is only useful if the seeds it reports really are what you'll get in the game.

- **The random number generator is reproduced bit for bit**: Balatro's `pseudohash` and
  `pseudoseed`, the 13-decimal rounding the game does through `string.format("%.13f")`
  (reproduced exactly, including the half-way cases a plain `floor(x * 1e13 + 0.5)` gets
  wrong about once in 2,800 draws), and LuaJIT's `math.random` generator.
- **No fused multiply-adds.** GPU compilers are allowed to merge `a * b + c` into one
  instruction that rounds once instead of twice. That is a different result in the last bit,
  and a different last bit sends a random stream down a different path. The kernel turns this
  off (`#pragma OPENCL FP_CONTRACT OFF`).
- **The GPU is cross-checked by a separate CPU generator**, written independently in Kotlin.
  The first 64 hits of every GPU launch are recomputed on the CPU, and any disagreement stops
  the search. Every seed that makes your results list is rescanned in full on the CPU to
  build its report. The rare seeds the GPU can't decide on its own (long chains of rerolls)
  are handed to the CPU and solved there.
- **Confirmed in game** on seeds where the generator's rounding matters.

## How the GPU search works

**Quick checks before the full scan.** When a search starts, a planner times each required
condition on a sample of seeds and turns the cheap, selective ones into quick checks. A
check generates only what it needs, for example just the Buffoon packs of antes 1 and 2, and
drops a seed as soon as a required card can no longer appear. Only seeds that pass every
check get the full scan: shops, vouchers, tags, bosses and scoring.

**Keeping the GPU's lanes busy.** A GPU runs threads in groups of 32 that execute the same
instruction together, each on its own seed. When one seed opens a Buffoon pack and its
neighbours don't, the others sit idle while it deals its cards. The first versions of the
kernel had only about a fifth of the lanes doing useful work, so most of the speed-ups are
about fixing that:

- **Split packs:** a check first rolls an ante's pack types, then deals each pack family's
  cards in one loop, instead of pack by pack.
- **Two rounds:** the first quick check runs on a whole batch of seeds, and only the few
  survivors go on to the rest, grouped together so they don't hold back the seeds that died
  early.
- **Stage-major first check:** the first check runs over a batch of seeds one kind of work at
  a time. Every seed's pack rolls, then every rarity, then every edition, then the jokers of
  each rarity, and similarly for tarots, planets, spectral cards and Souls. Before each step
  the work is sorted so the 32 lanes of a group get equal amounts. This roughly doubled the
  speed of most searches.
- **Split rounds:** the first check and everything after it are compiled as two separate GPU
  programs, so each gets its own register budget.

Each random stream still sees exactly the same draws in the same order as in the game, so
none of this changes a single result.

**Self-tuning.** Every GPU setting defaults to *auto* and is chosen from what the GPU reports
(compute units, threads per unit, shared memory) and from what the search needs. The chunk
size is timed to about a second per launch, and one option, a lookup table for the random
generator, is simply measured: the first chunks of a search try both and keep the faster.
No setting depends on the GPU's brand or model name.

## Seeds and indexes

Seeds use the 35 characters `1-9` and `A-Z`. The finder numbers every seed of 1 to 8
characters in order, shortest first; `--start-index` and `--end-index` pick a range of those
numbers, and the default searches the first 312 million.

The game's own random seeds are always 8 characters without the letter O. Shorter seeds and
seeds containing O only come from typing them in. To search only 8-character seeds, start at
index `66,231,629,135`.

## Command-line options

Every option is optional; run with `--help` for the full list. Numbers accept `k`, `m`, `b`
and `t` (`250k`, `312m`, `1.5b`).

**The search**

| Option | What it does |
|---|---|
| `--conditions FILE` | Search the conditions saved in FILE without the web page, then exit |
| `--start-index N`, `--end-index N` | The range of seeds to search (default 0 to 312m) |
| `--max-results N` | How many of the best seeds to keep (default 200) |
| `--checkpoint N` | Save results every N seeds (default every tenth of the range; 0 turns it off) |
| `--disable-gpu` | Search on the CPU only |
| `--examine SEED` | Print one seed's antes 1–8 in full and exit |

**The web page**

| Option | What it does |
|---|---|
| `--port N` | Port for the page (default 7777) |
| `--host ADDR` | Address it listens on (default this computer only; `0.0.0.0` lets anyone connect) |
| `--no-browser` | Don't open a browser |

**GPU tuning.** All of these choose themselves; set one only to experiment. They're also on
the web page under *GPU tuning*.

| Option | Auto chooses |
|---|---|
| `--chunk N` | Seeds per launch: about one second of work (0.4 s on a GPU that drives a screen) |
| `--local-size N` | Work-group size: the largest up to 256 the GPU and kernel allow |
| `--global-size N` | Work items per compute unit (default 4096) |
| `--nv-registers N` | NVIDIA: register cap so an SM holds its full number of threads |
| `--nv-registers-r1 N` | NVIDIA: the first check's own cap (twice that, or the same for Soul/tarot/spectral checks) |
| `--stage-major`, `--split-rounds`, `--two-round`, `--split-packs` | On |
| `--rng-table off\|4\|8\|16` | Measured per search: kept at 4 only where it's faster |
| `--prefix-cache auto\|local\|private` | Shared memory only if it fits without shrinking the work group |
| `--state-reset auto\|tags\|clear` | Tags whenever they fit |
| `--eager-prefix`, `--no-inline` | Off |

## Building from source

You need JDK 25; the Gradle wrapper is included.

```
./gradlew release       # everything for your OS, into build/release/
./gradlew bundleRun     # Linux: the single self-extracting .run file
./gradlew shadowJar     # just the jar (run it with Java 25)
```

The Java runtime bundled into a build matches the OS it was built on, so build the Windows
files on Windows and the Linux files on Linux. Releases are built by GitHub Actions: pushing
a tag such as `v2.1` builds both and attaches them to a new release.

**Checking a change.** The CPU is the reference. Compare a GPU run against
`--disable-gpu` over the same seed range, with `--calibration-seeds 0` and a range with
fewer hits than `--max-results`, so both keep every match: the two result lists must be
identical. On a machine without a GPU, a CPU OpenCL runtime such as pocl runs the kernel too.

### Project layout

| File | What's in it |
|---|---|
| `src/main/resources/search.cl` | The OpenCL kernel: generation, matching and scoring on the GPU |
| `kernel/ClSearch.kt` | GPU host: stream tables, kernel builds, auto-tuning, launches, CPU cross-checks |
| `analyzer/Rng.kt`, `Generator.kt` | Balatro's RNG and the CPU seed generator, the reference the GPU is checked against |
| `analyzer/Conditions.kt`, `Planner.kt` | Conditions, scoring, and the planner that picks the quick checks |
| `analyzer/SearchRun.kt`, `Main.kt`, `Cli.kt` | Running a search, command-line options |
| `analyzer/WebServer.kt`, `web/index.html` | The web page (the JDK's built-in HTTP server, no extra libraries) |
| `analyzer/Items.kt` | Card pools, originally from [Immolate](https://github.com/SpectralPack/Immolate) |
| `prototypes/` | Throwaway experiments, not part of the program |

---

## Credits

Card pools and much of the generation logic began with
[Immolate](https://github.com/SpectralPack/Immolate) by SpectralPack. Balatro is made by
LocalThunk and published by Playstack. This is an unofficial fan project, not affiliated
with either.
