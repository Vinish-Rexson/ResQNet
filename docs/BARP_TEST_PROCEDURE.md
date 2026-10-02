# BARP Battery Measurement Procedure

BARP has no claimed battery-saving percentage until this procedure is run and its raw measurements are retained.

## Setup

1. Use two comparable Android phones with the same ResQNet debug build and Bluetooth permissions granted.
2. Put both phones near each other, start the mesh, and keep their screens off for one hour.
3. Keep each phone on the same network and power state for both runs. Do not charge either phone during a run.
4. Record the initial and final battery percentage and save `adb shell dumpsys batterystats` output for both phones.

## Run A: forced Normal

1. Open **Diagnostics** on each phone and choose **Force Normal** under BARP override.
2. Start mesh relaying, send a fixed set of messages at the same times used in Run B, and keep both screens off for one hour.
3. Save `adb shell dumpsys batterystats > normal-batterystats.txt` from each phone after the run.

## Run B: forced Conservation

1. Reset Android battery statistics before the run with `adb shell dumpsys batterystats --reset` on each phone.
2. Choose **Force Conservation** on both phones.
3. Repeat the same one-hour, screen-off run and message schedule.
4. Save `adb shell dumpsys batterystats > conservation-batterystats.txt` from each phone.

Compare the initial/final battery readings and the saved batterystats files. Also record message delivery, relay acknowledgement, and elapsed delivery time so an apparent energy reduction is not accepted if it reduces mesh reliability.
