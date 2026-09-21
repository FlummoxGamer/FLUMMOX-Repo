import json
import os

version = int(os.environ.get("NEW_VERSION", "100"))
branch = os.environ.get("OUTPUT_BRANCH", "builds")
repo = os.environ.get("GITHUB_REPOSITORY", "FlummoxGamer/FLUMMOX-Repo")
base = f"https://raw.githubusercontent.com/{repo}/{branch}"

with open("builds/plugins.json") as f:
    data = json.load(f)

for p in data:
    n = p.get("internalName", "")
    if n:
        p["url"] = f"{base}/{n}.cs3"
        p["version"] = version

with open("builds/plugins.json", "w") as f:
    json.dump(data, f, indent=2)

print(f"Patched {len(data)} plugin(s) with version {version} → {branch}")
