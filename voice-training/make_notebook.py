# Builds jarvis_my_voice.ipynb (run: python3 make_notebook.py). Kept as a script so the notebook is easy to review.
import json, pathlib
here = pathlib.Path(__file__).parent
sentences = (here.parent / "android/app/src/main/assets/voice/sentences.txt").read_text(encoding="utf-8").strip().splitlines()
to_sherpa = (here / "to_sherpa.py").read_text(encoding="utf-8")

def md(s): return {"cell_type": "markdown", "metadata": {}, "source": s.strip("\n")}
def code(s): return {"cell_type": "code", "metadata": {}, "execution_count": None, "outputs": [], "source": s.strip("\n")}

cells = [
md("""
# Jarvis: make my own voice (free)

This notebook turns a recording of **your voice** into a voice file that Jarvis uses offline on your phone.

**Before you start**
1. In Google Drive, make a folder named **Jarvis** (in "My Drive").
2. Put **one** of these in it:
   - **Best:** a recording of 20+ minutes of only your voice (any topic, English, quiet room). `.mp3`, `.m4a`, `.wav` all work. Several files are fine.
   - **Or** `jarvis-voice-data.zip` exported from Jarvis setup > Record my voice.
   - **Or (quick, lower quality):** a 1-minute recording named `short-clip` (for example `short-clip.m4a`).
3. In the menu: **Runtime > Change runtime type > T4 GPU > Save**.

**Then:** press **Runtime > Run all**. Allow Google Drive when asked. It takes about 3 to 5 hours.
If Colab stops (it can after a few hours), open the notebook again and press **Run all** again: it continues from where it stopped.

At the end you get **jarvis-my-voice.zip** in your Drive's Jarvis folder. Download it to your phone, then in Jarvis setup tap **Use my voice**.

Your recordings stay in your own Google Drive.
"""),
code("""
#@title 1. Settings and Google Drive
EXTRA_EPOCHS = 400   # more = closer to your voice but longer. 300-600 is a good range.
JARVIS = "/content/drive/MyDrive/Jarvis"

import os, subprocess, glob, shutil, json, time, pathlib
from google.colab import drive
drive.mount("/content/drive")
os.makedirs(JARVIS, exist_ok=True)
gpu = subprocess.run(["nvidia-smi", "-L"], capture_output=True, text=True).stdout
print(gpu or "NO GPU! Use Runtime > Change runtime type > T4 GPU, then Run all again.")
assert gpu, "A GPU is needed."
print("Files in your Jarvis folder:", os.listdir(JARVIS))
"""),
code("""
#@title 2. Install the free tools (about 5 minutes)
PIPER_COMMIT = "d76f335d9eb5dea5ef6d7d70b2d6114e6303fa08"  # piper1-gpl, fixed version
!apt-get -qq update && apt-get -qq install -y build-essential cmake ninja-build ffmpeg > /dev/null
if not os.path.exists("/content/piper1-gpl"):
    !git clone -q https://github.com/OHF-voice/piper1-gpl.git /content/piper1-gpl
    !cd /content/piper1-gpl && git checkout -q {PIPER_COMMIT}
%cd /content/piper1-gpl
!pip -q install -e '.[train]'
!./build_monotonic_align.sh
!python setup.py build_ext --inplace > /dev/null
!pip -q install faster-whisper==1.1.1 onnx sherpa-onnx==1.12.33 soundfile librosa
print("Tools ready.")
"""),
code("""
#@title 3. Prepare your recordings (writes the text automatically)
import soundfile as sf, librosa, numpy as np
DATA = "/content/data"; WAVS = DATA + "/wavs"
os.makedirs(WAVS, exist_ok=True)
rows = []
files = os.listdir(JARVIS)
AUDIO = (".mp3", ".m4a", ".wav", ".ogg", ".opus", ".aac", ".flac", ".mp4", ".amr", ".3gp", ".webm")
SENTENCES = """ + json.dumps(sentences, ensure_ascii=False) + """

def save22k(y, sr, name):
    if sr != 22050: y = librosa.resample(y, orig_sr=sr, target_sr=22050)
    y = y / max(1e-6, np.abs(y).max()) * 0.9
    sf.write(f"{WAVS}/{name}.wav", y, 22050, subtype="PCM_16")

if "jarvis-voice-data.zip" in files:
    print("Using the sentences you recorded in Jarvis.")
    !unzip -o -q "{JARVIS}/jarvis-voice-data.zip" -d /content/zipdata
    for line in open("/content/zipdata/metadata.csv", encoding="utf-8"):
        if "|" not in line: continue
        uid, text = line.rstrip("\\n").split("|", 1)
        y, sr = librosa.load(f"/content/zipdata/wavs/{uid}.wav", sr=None, mono=True)
        save22k(y, sr, uid); rows.append((uid, text))
elif any(f.startswith("short-clip") for f in files) and not [f for f in files if f.lower().endswith(AUDIO) and not f.startswith("short-clip")]:
    print("Quick mode: making training sentences in your voice from your short clip (about 20 minutes).")
    clip = [f for f in files if f.startswith("short-clip")][0]
    !wget -q -nc https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2 -P /content
    !tar xjf /content/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2 -C /content
    import sherpa_onnx as so
    P = glob.glob("/content/sherpa-onnx-pocket-tts-int8-*/")[0]
    pc = so.OfflineTtsPocketModelConfig(lm_flow=P+"lm_flow.int8.onnx", lm_main=P+"lm_main.int8.onnx", encoder=P+"encoder.onnx", decoder=P+"decoder.int8.onnx",
                                        text_conditioner=P+"text_conditioner.onnx", vocab_json=P+"vocab.json", token_scores_json=P+"token_scores.json")
    mc = so.OfflineTtsModelConfig(num_threads=2); mc.pocket = pc
    tts = so.OfflineTts(so.OfflineTtsConfig(model=mc))
    ref, rsr = librosa.load(f"{JARVIS}/{clip}", sr=24000, mono=True)
    ref = ref[: 24000 * 60]
    g = so.GenerationConfig(); g.reference_audio = ref.tolist(); g.reference_sample_rate = 24000
    for i, text in enumerate(SENTENCES):
        a = tts.generate(text, g)
        save22k(np.array(a.samples, dtype=np.float32), a.sample_rate, f"q{i:04d}"); rows.append((f"q{i:04d}", text))
        if i % 20 == 0: print(i, "/", len(SENTENCES))
else:
    long_files = [f for f in files if f.lower().endswith(AUDIO)]
    assert long_files, "Put your recording (.mp3/.m4a/.wav) in the Jarvis folder of your Google Drive, then Run all again."
    print("Writing down what you said in:", long_files)
    from faster_whisper import WhisperModel
    wm = WhisperModel("large-v3", device="cuda", compute_type="float16")
    n = 0
    for f in long_files:
        y, sr = librosa.load(f"{JARVIS}/{f}", sr=22050, mono=True)
        segs, _ = wm.transcribe(f"{JARVIS}/{f}", language="en", vad_filter=True, beam_size=5)
        for s in segs:
            dur = s.end - s.start
            if dur < 1.0 or dur > 15 or s.no_speech_prob > 0.5 or s.avg_logprob < -1.0 or len(s.text.strip()) < 3: continue
            a, b = int(max(0, s.start - 0.1) * 22050), int(min(len(y) / 22050, s.end + 0.15) * 22050)
            save22k(y[a:b], 22050, f"r{n:05d}"); rows.append((f"r{n:05d}", s.text.strip())); n += 1
    del wm

with open(DATA + "/metadata.csv", "w", encoding="utf-8") as m:
    for uid, text in rows: m.write(f"{uid}.wav|{text}\\n")
mins = sum(sf.info(f"{WAVS}/{u}.wav").duration for u, _ in rows) / 60
print(f"Ready: {len(rows)} clips, {mins:.1f} minutes of your voice.")
if mins < 10: print("Tip: more than 15 minutes sounds much better. It will still work.")
"""),
code("""
#@title 4. Train your voice (2-5 hours). Safe to re-run: it continues.
CKPT_DIR = JARVIS + "/training"; os.makedirs(CKPT_DIR, exist_ok=True)
saved = CKPT_DIR + "/last.ckpt"
if os.path.exists(saved):
    start = saved; print("Continuing from your saved training.")
else:
    start = "/content/lessac.ckpt"
    if not os.path.exists(start):
        !wget -q -O /content/lessac.ckpt "https://huggingface.co/datasets/rhasspy/piper-checkpoints/resolve/main/en/en_US/lessac/medium/epoch%3D2164-step%3D1355540.ckpt"
    print("Starting from a ready-made English voice (Lessac medium).")
MAX_EPOCHS = 2164 + EXTRA_EPOCHS
cmd = ["python", "-m", "piper.train", "fit",
       "--data.voice_name", "jarvis_me", "--data.csv_path", DATA + "/metadata.csv", "--data.audio_dir", WAVS,
       "--model.sample_rate", "22050", "--data.espeak_voice", "en-us", "--data.cache_dir", "/content/cache",
       "--data.config_path", "/content/config.json", "--data.batch_size", "16",
       "--model.mos_metric", "none", "--trainer.max_epochs", str(MAX_EPOCHS),
       "--trainer.default_root_dir", "/content/train", "--ckpt_path", start]
log = open("/content/train.log", "w")
p = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, cwd="/content/piper1-gpl")
last_copy = time.time()
def newest():
    c = glob.glob("/content/train/lightning_logs/*/checkpoints/last.ckpt")
    return max(c, key=os.path.getmtime) if c else None
while p.poll() is None:
    time.sleep(60)
    tail = open("/content/train.log").read()[-400:].replace("\\r", "\\n").splitlines()
    print(time.strftime("%H:%M"), tail[-1][:160] if tail else "")
    if time.time() - last_copy > 15 * 60 and newest():
        shutil.copy(newest(), saved + ".tmp"); os.replace(saved + ".tmp", saved); shutil.copy("/content/config.json", CKPT_DIR + "/config.json")
        last_copy = time.time(); print("  (progress saved to your Drive)")
if newest():
    shutil.copy(newest(), saved + ".tmp"); os.replace(saved + ".tmp", saved); shutil.copy("/content/config.json", CKPT_DIR + "/config.json")
print("Training finished with code", p.returncode, "- if it is not 0, see /content/train.log")
"""),
code("""
#@title 5. Make jarvis-my-voice.zip and listen to a sample
""" + to_sherpa + """
OUT = "/content/out"; shutil.rmtree(OUT, ignore_errors=True); os.makedirs(OUT)
!python -m piper.train.export_onnx --checkpoint "{JARVIS}/training/last.ckpt" --output-file /content/raw.onnx
cfg = "/content/config.json" if os.path.exists("/content/config.json") else JARVIS + "/training/config.json"
convert("/content/raw.onnx", cfg, OUT + "/model.onnx", OUT + "/tokens.txt")
!wget -q -nc https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2 -P /content
!tar xjf /content/vits-piper-en_US-lessac-medium.tar.bz2 -C /content
shutil.copytree("/content/vits-piper-en_US-lessac-medium/espeak-ng-data", OUT + "/espeak-ng-data")
import sherpa_onnx as so
v = so.OfflineTtsVitsModelConfig(model=OUT + "/model.onnx", tokens=OUT + "/tokens.txt", data_dir=OUT + "/espeak-ng-data")
tts = so.OfflineTts(so.OfflineTtsConfig(model=so.OfflineTtsModelConfig(vits=v, num_threads=2)))
a = tts.generate("Hello Boss, this is Jarvis speaking in your voice. How do I sound?", sid=0, speed=1.0)
sf.write("/content/sample.wav", np.array(a.samples), a.sample_rate)
shutil.make_archive("/content/jarvis-my-voice", "zip", OUT)
shutil.copy("/content/jarvis-my-voice.zip", JARVIS + "/jarvis-my-voice.zip")
print("Done! jarvis-my-voice.zip is in your Drive's Jarvis folder. Download it to your phone, then Jarvis setup > Use my voice.")
from IPython.display import Audio; Audio("/content/sample.wav")
"""),
]
nb = {"cells": cells, "metadata": {"accelerator": "GPU", "colab": {"provenance": [], "gpuType": "T4"},
      "kernelspec": {"name": "python3", "display_name": "Python 3"}, "language_info": {"name": "python"}},
      "nbformat": 4, "nbformat_minor": 0}
(here / "jarvis_my_voice.ipynb").write_text(json.dumps(nb, indent=1, ensure_ascii=False), encoding="utf-8")
print("wrote", here / "jarvis_my_voice.ipynb")
