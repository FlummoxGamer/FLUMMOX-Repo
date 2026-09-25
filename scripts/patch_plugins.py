import json
import os

branch = os.environ.get("OUTPUT_BRANCH", "builds")
repo = os.environ.get("GITHUB_REPOSITORY", "FlummoxGamer/FLUMMOX-Repo")
base = f"https://raw.githubusercontent.com/{repo}/{branch}"

# Read all *_version keys from gradle.properties
props = {}
with open("src/gradle.properties") as f:
    for line in f:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        props[k.strip()] = v.strip()

def version_for(internal_name: str) -> int:
    # "Otakutsu" → "otakutsu_version", "BingeCloud" → "bingecloud_version"
    key = internal_name.lower() + "_version"
    v = props.get(key)
    if v is None:
        print(f"WARN: {key} missing in gradle.properties — defaulting to 1")
        return 1
    return int(v)

with open("builds/plugins.json") as f:
    data = json.load(f)

for p in data:
    n = p.get("internalName", "")
    if not n:
        continue
    p["url"] = f"{base}/{n}.cs3"
    p["version"] = version_for(n)

with open("builds/plugins.json", "w") as f:
    json.dump(data, f, indent=2)

print(f"Patched {len(data)} plugin(s) for {branch}: "
      + ", ".join(f"{p['internalName']}=v{p['version']}" for p in data))
