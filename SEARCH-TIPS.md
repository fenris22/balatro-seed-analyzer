# Making searches fast

How you set up your conditions can change a search's speed by 10× or more. All the numbers
below were measured on an RX 9070 XT with 8-character seeds; other GPUs are faster, but the
ratios between searches stay about the same.

## Why some searches are fast

The finder generates each seed ante by ante, and throws it away the moment it can't
possibly match. A **required** condition has a last ante (the end of its window); once that
ante is over and the condition still isn't met, the seed is dropped. Everything after that
point is never generated.

Before the search starts, a planner looks at your required conditions that come from
**packs or Souls**, and turns the cheap, selective ones into *quick checks*. These generate
only what they need (for example, only the Buffoon packs of antes 1–2) and run on every seed.
Only the seeds that pass them get the full scan: shops, vouchers, tags, bosses and scoring.
The log shows the quick checks it chose ("Prefilter stages") and roughly what fraction of
seeds each lets through.

So a fast search is one where **most seeds are dropped early, by a cheap check**.

## The rules

**1. Mark at least one condition as Required.** Without one, every seed is generated to the
end of every window.

> Tested on a RX 9070XT

| Negative Blueprint or Brainstorm from a pack, antes 1–2 | Seeds/s |
|---|---|
| Only scored | 50M |
| Required | 131M |

**2. Close the window as early as you can.** A seed can only be dropped after the last ante
of the window, so every extra ante is generated for almost every seed.

> Tested on a RX 9070XT

| Negative Blueprint or Brainstorm from a pack, required, antes 1–… | Seeds/s |
|---|---|
| 1 | 203M |
| 2 | 131M |
