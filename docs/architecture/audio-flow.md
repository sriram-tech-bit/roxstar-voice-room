# Audio flow

```mermaid
flowchart LR
  Mic[Microphone] --> Perm[RECORD_AUDIO permission]
  Perm --> In[Oboe input stream]
  In --> Echo[Echo delay buffer in native callback]
  Echo --> Wav[PCM16 WAV writer]
  Wav --> File[Local Draft file]
  File --> Store[Draft list name / time / duration]
  Store --> Play[Oboe output playback]
  Store --> Share[POST /rooms/:id/drafts metadata]
```

Implemented effect: **Echo** (250ms delay, 0.45 decay) inside the Oboe `onAudioReady` callback.

Start / Stop finalize a WAV file. Cancel stops the stream and deletes the in-progress file.

Lifecycle: recording and playback cannot overlap; activity destroy stops playback.
