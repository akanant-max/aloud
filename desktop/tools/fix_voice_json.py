"""Makes a Piper voice's settings file readable by the Piper build Aloud ships (2023.11.14-2).

Newer settings files list multi-character phonemes such as "aɪ" in phoneme_id_map, which that
build rejects. It only ever looks up single characters, so those entries are dropped.
"""
import json
import sys

for path in sys.argv[1:]:
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    ids = data.get("phoneme_id_map", {})
    kept = {k: v for k, v in ids.items() if len(k) == 1}
    if len(kept) != len(ids):
        data["phoneme_id_map"] = kept
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        print(f"{path}: dropped {len(ids) - len(kept)} multi-character phonemes")
