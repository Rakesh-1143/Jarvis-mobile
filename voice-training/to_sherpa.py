# Converts a Piper voice (model.onnx + config.json) into the files sherpa-onnx needs.
import json, onnx, sys
def convert(onnx_in, config_in, onnx_out, tokens_out):
    cfg = json.load(open(config_in, encoding="utf-8"))
    with open(tokens_out, "w", encoding="utf-8") as f:
        for sym, ids in cfg["phoneme_id_map"].items():
            f.write(f"{sym} {ids[0]}\n")
    m = onnx.load(onnx_in)
    del m.metadata_props[:]
    meta = {"model_type": "vits", "comment": "piper",
            "language": (cfg.get("language") or {}).get("name_english", "English"),
            "voice": cfg["espeak"]["voice"], "has_espeak": 1,
            "n_speakers": cfg.get("num_speakers", 1), "sample_rate": cfg["audio"]["sample_rate"]}
    for k, v in meta.items():
        p = m.metadata_props.add(); p.key = k; p.value = str(v)
    onnx.save(m, onnx_out)
if __name__ == "__main__" and len(sys.argv) == 5 and sys.argv[1].endswith(".onnx"):
    convert(*sys.argv[1:5])
