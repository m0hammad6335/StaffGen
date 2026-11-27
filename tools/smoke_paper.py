"""Run StaffGen on a throwaway copy of an already prepared Paper installation.

The source directory supplies an extracted server, libraries and the operator's
accepted EULA. World files and plugin data are never opened from that directory.
"""

import argparse
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import tempfile
import threading
import time
import zipfile


def classpath(source: Path, launcher: Path) -> tuple[str, list[Path]]:
    with zipfile.ZipFile(launcher) as archive:
        main = archive.read("META-INF/main-class").decode().strip()
        version = archive.read("META-INF/versions.list").decode().strip().split("\t")[2]
        dependencies = archive.read("META-INF/libraries.list").decode().splitlines()

    # Paperclip's library list takes precedence over the upstream vanilla set.
    preferred = [source / "libraries" / line.split("\t")[2] for line in dependencies]
    libraries = list(dict.fromkeys([*preferred, *sorted((source / "libraries").rglob("*.jar"))]))
    files = [source / "versions" / version, *libraries]
    for file in files:
        if not file.is_file():
            raise FileNotFoundError(f"Paper installation is incomplete: {file}")
    return main, files


def await_line(output, process, predicate, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            line = output.get(timeout=1)
        except queue.Empty:
            if process.poll() is not None:
                raise RuntimeError(f"Paper exited early with code {process.returncode}")
            continue
        if predicate(re.sub(r"\x1b\[[0-9;]*m", "", line)):
            return
    raise TimeoutError("Expected server response did not arrive")


def run(args):
    source = args.server.resolve()
    eula = source / "eula.txt"
    if "eula=true" not in eula.read_text().splitlines():
        raise ValueError("Provide an installation with an accepted Minecraft EULA")
    main, files = classpath(source, source / args.launcher)
    with tempfile.TemporaryDirectory(prefix="staffgen-paper-") as directory:
        work = Path(directory)
        shutil.copy(eula, work / "eula.txt")
        (work / "plugins").mkdir()
        shutil.copy(args.plugin, work / "plugins")
        (work / "server.properties").write_text(
            "server-ip=127.0.0.1\nserver-port=0\nonline-mode=true\n"
            "level-name=smoke-world\nlevel-type=minecraft:normal\n"
            "generate-structures=false\nview-distance=3\nsimulation-distance=3\n",
            encoding="ascii",
        )
        # Java argument files avoid the Windows process command line length limit.
        paths = os.pathsep.join(file.resolve().as_posix() for file in files)
        (work / "launch.args").write_text(
            f'-Xms256m\n-Xmx1200m\n-XX:ActiveProcessorCount=2\n'
            f'-cp\n"{paths}"\n{main}\n--nogui\n', encoding="utf-8"
        )
        process = subprocess.Popen(
            [args.java, "@launch.args"], cwd=work, stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            encoding="utf-8", errors="replace", bufsize=1,
        )
        output = queue.Queue()
        transcript = []

        def read_console():
            for line in process.stdout:
                transcript.append(line)
                output.put(line)
                print(line.rstrip(), flush=True)

        reader = threading.Thread(target=read_console, daemon=True)
        reader.start()
        try:
            await_line(output, process, lambda s: "Done (" in s, args.timeout)
            for command, match in [
                ("version StaffGen", lambda s: "StaffGen version " in s),
                ("staffgen:sg help", lambda s: "/sg" in s),
                ("staffgen:sg reload", lambda s: "reload" in s.lower() or "configuration loaded" in s.lower()),
            ]:
                process.stdin.write(command + "\n")
                process.stdin.flush()
                await_line(output, process, match, 20)
            process.stdin.write("stop\n")
            process.stdin.flush()
            if process.wait(timeout=60) != 0:
                raise RuntimeError("Paper did not exit cleanly")
            reader.join(5)
            if not any("Enabling StaffGen " in line for line in transcript):
                raise RuntimeError("StaffGen enable event missing from server log")
            if any("Error occurred while enabling StaffGen" in line or "Startup failed" in line
                   for line in transcript):
                raise RuntimeError("StaffGen startup failed")
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            process.stdin.close()
            reader.join(3)
            process.stdout.close()
    print("StaffGen startup, console commands and shutdown passed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True, type=Path)
    parser.add_argument("--launcher", default="server.jar")
    parser.add_argument("--plugin", required=True, type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--timeout", type=int, default=180)
    run(parser.parse_args())


