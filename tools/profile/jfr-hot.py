#!/usr/bin/env python3
"""Where a thread spends its time in a JFR recording (CPU samples). Uses the JDK's `jfr print` (no extra packages).
usage: jfr-hot.py RECORDING.jfr [THREAD-NAME-SUBSTRING="Server thread"] [TOP=30] [--prefix pkg.]  (prefix: inclusive counts only for frames starting with it)
Prints: samples of that thread, the top methods by self time, and by inclusive time (anywhere on the stack)."""
import subprocess, sys, re, collections, os
rec = sys.argv[1]; want = sys.argv[2] if len(sys.argv) > 2 else "Server thread"; top = int(sys.argv[3]) if len(sys.argv) > 3 else 30
prefix = sys.argv[sys.argv.index("--prefix") + 1] if "--prefix" in sys.argv else ""
jfr = os.path.join(os.environ.get("JAVA_HOME") or subprocess.check_output(["/usr/libexec/java_home", "-v", "25"]).decode().strip(), "bin", "jfr")
out = subprocess.run([jfr, "print", "--events", "jdk.ExecutionSample", "--stack-depth", "96", rec], capture_output=True, text=True).stdout
selfc, incl, total, threads = collections.Counter(), collections.Counter(), 0, collections.Counter()
for block in out.split("jdk.ExecutionSample {")[1:]:
    m = re.search(r'sampledThread = "([^"]*)"', block)
    name = m.group(1) if m else "?"
    threads[name] += 1
    if want not in name: continue
    frames = re.findall(r'^\s+([\w$.<>/]+\.[\w$<>]+)\(', block, re.M)
    if not frames: continue
    total += 1
    selfc[frames[0]] += 1
    for f in set(frames):
        if f.startswith(prefix): incl[f] += 1
print(f"{total} samples on '{want}' (all threads: {sum(threads.values())}; top threads: {threads.most_common(6)})")
print("\nself:")
for f, c in selfc.most_common(top): print(f"  {100*c/max(total,1):5.1f}%  {f}")
print("\ninclusive:")
for f, c in incl.most_common(top): print(f"  {100*c/max(total,1):5.1f}%  {f}")
