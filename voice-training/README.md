# Jarvis speaks in your own voice (free)

1. **Record.** Either record 20+ minutes of only your voice with any recorder app (quiet room, English, any topic),
   or use Jarvis setup > *Record my voice* and tap *Export* (saves `jarvis-voice-data.zip` in Downloads),
   or for a quick test record 1 minute and name it `short-clip` (lower quality).
2. **Put it in Google Drive** in a folder named `Jarvis` (inside My Drive).
3. **Open the notebook** `jarvis_my_voice.ipynb` in Google Colab (free):
   Runtime > Change runtime type > **T4 GPU** > Save, then **Runtime > Run all**. Allow Google Drive.
   It takes about 3 to 5 hours. If Colab stops, open it again and press Run all: it continues.
4. **Get the voice.** When it finishes, `jarvis-my-voice.zip` is in your Drive's Jarvis folder. It also plays a sample.
   Download it to your phone.
5. **Use it.** Jarvis setup > *Use my voice* > pick `jarvis-my-voice.zip` > *Say hello in my voice*.

Everything is free: Google Colab's free GPU, Piper (training) and sherpa-onnx (on the phone). Your recordings stay
in your own Google Drive and phone. If your voice file can't load, Jarvis uses the phone voice instead.

`make_notebook.py` builds the notebook; `to_sherpa.py` converts a Piper voice for sherpa-onnx (tested).
