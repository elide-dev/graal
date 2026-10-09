#!/usr/bin/env python3
"""Checks that every org.graalvm dependency of the deployed artifacts is deployed too.

    ci/elide/check-maven-closure.py <repo-dir>

The release deploys some distributions by name (deploy-maven.sh), so a distribution that upstream
adds as a dependency of a deployed one would otherwise be missing from the repository, and every
consumer of the artifact that depends on it would fail to resolve.
"""
import os
import re
import sys


def main():
    root = sys.argv[1]
    deployed, poms = set(), []
    for dirpath, _, files in os.walk(root):
        for name in files:
            if name.endswith(".pom"):
                parts = os.path.relpath(os.path.join(dirpath, name), root).split(os.sep)
                coordinate = (".".join(parts[:-3]), parts[-3])
                deployed.add(coordinate)
                poms.append((coordinate, os.path.join(dirpath, name)))
    missing = {}
    for (group, artifact), path in poms:
        with open(path, encoding="utf-8") as f:
            text = f.read()
        for dependency in re.findall(r"<dependency>(.*?)</dependency>", text, re.S):
            dep_group = re.search(r"<groupId>(.*?)</groupId>", dependency).group(1)
            dep_artifact = re.search(r"<artifactId>(.*?)</artifactId>", dependency).group(1)
            if dep_group.startswith("org.graalvm") and (dep_group, dep_artifact) not in deployed:
                missing.setdefault(f"{dep_group}:{dep_artifact}", set()).add(f"{group}:{artifact}")
    for dependency, users in sorted(missing.items()):
        print(f"::error::{dependency} is not deployed, but {', '.join(sorted(users))} depend(s) on it")
    if missing:
        sys.exit(1)
    print(f"maven closure: all org.graalvm dependencies of {len(poms)} artifacts are deployed")


if __name__ == "__main__":
    main()
