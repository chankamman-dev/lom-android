using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.Networking;

namespace LomAndroid
{
    // Stands in for the Wwise sound engine. The game only ever posts events by name and sets a few
    // RTPCs. events.tsv (made by lomtool audio-events from the banks' hierarchy) says what each event
    // does: stop a bus, play a one-shot picked at random, or start a music playlist whose segment
    // loops between its entry and exit cues. gain.tsv carries the Wwise mix for every file.
    public class LomAudio : MonoBehaviour
    {
        const string Tag = "[LomAudio] ";

        class Rtpc { public float Value = 100, Target = 100, Speed; }

        class OneShot { public string Bus; public string[] Files; public int Last = -1; }

        // One music or ambience playlist: a clip of <File>, placed at PlayAt in the segment and
        // trimmed by Begin/End (End is negative), replayed every Exit - Entry ms.
        class Playlist
        {
            public string Bus, File;
            public double PlayAt, Begin, End, SrcDur, Entry, Exit, FadeIn, FadeOut;
            public int Loops;
        }

        class Switch { public uint Group; public string Default; public Dictionary<uint, string> Map = new Dictionary<uint, string>(); }

        // Something playing on a bus: a one-shot or a playlist using two sources in turn, so that
        // one iteration's tail and the next one's lead-in can overlap like they do in Wwise.
        class Voice
        {
            public string Bus;
            public AudioSource[] Src;
            public float Fade = 1, FadeTarget = 1, FadeSpeed;
            public bool Paused;
            public double PausedAt;
            // playlist state
            public Playlist P;
            public string SwitchId;
            public AudioClip Clip;
            public double NextOrigin;
            public int Iteration, Next;
            public bool Pending;
            public double[] SrcEnd = new double[2], StartAt = new double[2], Origin = new double[2];
        }

        static LomAudio instance;
        static readonly Dictionary<string, Rtpc> rtpcs = new Dictionary<string, Rtpc>();
        readonly Dictionary<string, string[]> events = new Dictionary<string, string[]>();
        readonly Dictionary<string, OneShot> oneShots = new Dictionary<string, OneShot>();
        readonly Dictionary<string, Playlist> playlists = new Dictionary<string, Playlist>();
        readonly Dictionary<string, Switch> switches = new Dictionary<string, Switch>();
        readonly Dictionary<uint, uint> states = new Dictionary<uint, uint>();
        readonly Dictionary<string, AudioClip> sfx = new Dictionary<string, AudioClip>();
        // Linear gain per media file: the Wwise mix (node, parent and bus volumes) from gain.tsv.
        readonly Dictionary<string, float> gains = new Dictionary<string, float>();
        readonly Dictionary<string, int> busSerial = new Dictionary<string, int> { { "music", 0 }, { "env", 0 }, { "sfx", 0 } };
        readonly List<Voice> voices = new List<Voice>();
        readonly List<AudioSource> idle = new List<AudioSource>();
        readonly List<string> pending = new List<string>();
        readonly LinkedList<KeyValuePair<string, AudioClip>> loopClips = new LinkedList<KeyValuePair<string, AudioClip>>();
        string root;
        float listenerCheck;
        bool tableLoaded;

        static LomAudio Get()
        {
            if (instance == null)
            {
                var go = new GameObject("LomAudio");
                DontDestroyOnLoad(go);
                instance = go.AddComponent<LomAudio>();
            }
            return instance;
        }

        static Rtpc R(string name)
        {
            if (!rtpcs.TryGetValue(name, out var r))
            {
                rtpcs[name] = r = new Rtpc();
                // Defaults from Init.bnk: the volumes start full, the low-pass amount at none.
                if (name == "MusicLowPassValue") r.Value = r.Target = 0;
            }
            return r;
        }

        public static uint PostEvent(string name)
        {
            Get().Post(name);
            return 1;
        }

        public static void SetRtpc(string name, float value, int fadeMs)
        {
            Get();
            var r = R(name);
            r.Target = value;
            if (fadeMs <= 0) { r.Value = value; r.Speed = 0; }
            else r.Speed = Mathf.Abs(value - r.Value) / (fadeMs / 1000f);
        }

        public static float GetRtpc(string name) => R(name).Value;

        // For LomDebug: one line per voice.
        public static string Describe()
        {
            var sb = new System.Text.StringBuilder();
            double now = AudioSettings.dspTime;
            foreach (var v in Get().voices)
            {
                sb.Append(v.Bus).Append(v.P != null ? " loop " + v.P.File + " iter " + v.Iteration + " next in " + (v.NextOrigin - now).ToString("0.0") + "s" : " once " + v.Src[0].clip?.name)
                  .Append(" fade ").Append(v.Fade.ToString("0.00")).Append(v.Paused ? " paused" : "");
                foreach (var s in v.Src)
                    sb.Append(" | ").Append(s.isPlaying ? "play" : "stop").Append(" t=").Append(s.time.ToString("0.00")).Append(" vol=").Append(s.volume.ToString("0.00"))
                      .Append(" gain=").Append(s.GetComponent<LomGain>().Value.ToString("0.00"));
                sb.Append('\n');
            }
            return sb.Length == 0 ? "silent" : sb.ToString();
        }

        void Awake()
        {
            // The audio ships inside the OBB; streamingAssetsPath is a jar: URL, so it is read with UnityWebRequest.
            root = Application.streamingAssetsPath + "/audio";
            StartCoroutine(LoadTables());
            InputSystem.onDeviceChange += (d, c) => Debug.Log(Tag + "input " + c + ": " + d.layout + " / " + d.description.product);
            StartCoroutine(LogDevices());
            gameObject.AddComponent<LomDebug>();
        }

        IEnumerator LoadTables()
        {
            string text = null;
            yield return ReadText("events.tsv", t => text = t);
            if (text != null)
                foreach (var line in text.Split('\n'))
                {
                    var f = line.TrimEnd('\r').Split('\t');
                    switch (f[0])
                    {
                        case "E":
                            events[f[1]] = f[2].Length == 0 ? new string[0] : f[2].Split('|');
                            break;
                        case "S":
                            oneShots[f[1]] = new OneShot { Bus = f[2], Files = f[3].Length == 0 ? new string[0] : f[3].Split(',') };
                            break;
                        case "P":
                            playlists[f[1]] = new Playlist
                            {
                                Bus = f[2], File = f[3], PlayAt = D(f[4]), Begin = D(f[5]), End = D(f[6]), SrcDur = D(f[7]),
                                Entry = D(f[8]), Exit = D(f[9]), Loops = (int)D(f[10]), FadeIn = D(f[11]), FadeOut = D(f[12]),
                            };
                            break;
                        case "W":
                            var w = new Switch { Group = uint.Parse(f[2]), Default = f[3] };
                            foreach (var kv in f[4].Split(','))
                            {
                                var p = kv.Split('=');
                                if (p.Length == 2) w.Map[uint.Parse(p[0])] = p[1];
                            }
                            switches[f[1]] = w;
                            break;
                    }
                }
            yield return ReadText("gain.tsv", t => text = t);
            if (text != null)
                foreach (var line in text.Split('\n'))
                {
                    var f = line.TrimEnd('\r').Split('\t');
                    if (f.Length == 2) gains[f[0]] = Mathf.Pow(10, (float)D(f[1]) / 20);
                }
            Debug.Log(Tag + "events=" + events.Count + " oneshots=" + oneShots.Count + " playlists=" + playlists.Count + " gains=" + gains.Count + " from " + root);
            tableLoaded = true;
            foreach (var name in pending) Post(name);
            pending.Clear();

            // Wwise keeps every sound bank in memory, so a sound plays the moment it is posted.
            // The one-shots are under 1 MB in all; load them now instead of on first use.
            int loaded = 0;
            float t0 = Time.realtimeSinceStartup;
            foreach (var o in oneShots.Values)
                foreach (var id in o.Files)
                {
                    if (sfx.ContainsKey(id)) continue;
                    yield return LoadSfx(id);
                    if (sfx.ContainsKey(id)) loaded++;
                }
            Debug.Log(Tag + "preloaded " + loaded + " sounds in " + (Time.realtimeSinceStartup - t0).ToString("0.0") + "s");
        }

        IEnumerator LoadSfx(string id)
        {
            using (var req = UnityWebRequestMultimedia.GetAudioClip(Url(id), AudioType.OGGVORBIS))
            {
                ((DownloadHandlerAudioClip)req.downloadHandler).streamAudio = false;
                yield return req.SendWebRequest();
                if (req.result != UnityWebRequest.Result.Success)
                {
                    Debug.Log(Tag + "load failed " + id + ": " + req.error);
                    yield break;
                }
                var clip = DownloadHandlerAudioClip.GetContent(req);
                // Decode now, so the first play does not wait for it.
                clip.LoadAudioData();
                sfx[id] = clip;
            }
        }

        static double D(string s) => double.Parse(s, NumberStyles.Float, CultureInfo.InvariantCulture);

        IEnumerator ReadText(string file, System.Action<string> done)
        {
            using (var req = UnityWebRequest.Get(root + "/" + file))
            {
                yield return req.SendWebRequest();
                if (req.result == UnityWebRequest.Result.Success) done(req.downloadHandler.text);
                else { Debug.Log(Tag + file + ": " + req.error); done(null); }
            }
        }

        IEnumerator LogDevices()
        {
            yield return new WaitForSeconds(3);
            foreach (var d in InputSystem.devices) Debug.Log(Tag + "device " + d.layout + " / " + d.description.interfaceName + " / " + d.description.product);
        }

        AudioSource Source(string bus)
        {
            AudioSource s;
            if (idle.Count > 0) { s = idle[idle.Count - 1]; idle.RemoveAt(idle.Count - 1); }
            else
            {
                var go = new GameObject("src");
                go.transform.SetParent(transform);
                s = go.AddComponent<AudioSource>();
                s.playOnAwake = false;
                s.spatialBlend = 0;
                go.AddComponent<LomGain>();
                go.AddComponent<AudioLowPassFilter>().enabled = false;
            }
            s.loop = false;
            s.volume = 0;
            s.GetComponent<AudioLowPassFilter>().enabled = false;
            return s;
        }

        void Release(Voice v)
        {
            foreach (var s in v.Src)
            {
                s.Stop();
                s.clip = null;
                idle.Add(s);
            }
        }

        // The curves from Init.bnk. The three settings sliders drive bus volume on a dB-scaled curve
        // whose points are linear amplitude, so 0..100 is simply 0..1. The fades the game runs on
        // BackgroundMusic and EnvSound go linearly from -80 dB to 0 dB.
        static float Linear(float v) => Mathf.Clamp01(v / 100f);
        static float Fade(float v) => v <= 0 ? 0 : Mathf.Pow(10, (-80 + 0.8f * Mathf.Min(v, 100)) / 20);

        static float BusVolume(string bus)
        {
            float v = Linear(R("MasterVolume").Value);
            if (bus == "music") return v * Linear(R("MusicVolume").Value) * Fade(R("BackgroundMusic").Value);
            v *= Linear(R("SoundVolume").Value);
            return bus == "env" ? v * Fade(R("EnvSound").Value) : v;
        }

        void Update()
        {
            foreach (var r in rtpcs.Values)
                if (r.Value != r.Target)
                    r.Value = r.Speed <= 0 ? r.Target : Mathf.MoveTowards(r.Value, r.Target, r.Speed * Time.unscaledDeltaTime);

            float musicVol = BusVolume("music"), envVol = BusVolume("env"), sfxVol = BusVolume("sfx");
            float lp = R("MusicLowPassValue").Value;
            // MusicLowPassValue feeds the music bus LPF one to one; Wwise's 0..100 LPF is roughly
            // exponential in cutoff, from no filtering down to about 50 Hz.
            float cutoff = 20000 * Mathf.Pow(50 / 20000f, Mathf.Clamp01(lp / 100f));
            double now = AudioSettings.dspTime;

            for (int i = voices.Count - 1; i >= 0; i--)
            {
                var v = voices[i];
                if (v.Paused) continue;
                if (v.Fade != v.FadeTarget)
                    v.Fade = v.FadeSpeed <= 0 ? v.FadeTarget : Mathf.MoveTowards(v.Fade, v.FadeTarget, v.FadeSpeed * Time.unscaledDeltaTime);
                if (v.FadeTarget == 0 && v.Fade <= 0) { Release(v); voices.RemoveAt(i); continue; }
                float bus = v.Bus == "music" ? musicVol : v.Bus == "env" ? envVol : sfxVol;
                if (v.P == null)
                {
                    if (!v.Src[0].isPlaying) { Release(v); voices.RemoveAt(i); continue; }
                    v.Src[0].volume = bus * v.Fade;
                    continue;
                }
                if (!v.Pending && !Advance(v, now)) { Release(v); voices.RemoveAt(i); continue; }
                for (int k = 0; k < 2; k++)
                {
                    var s = v.Src[k];
                    var lpf = s.GetComponent<AudioLowPassFilter>();
                    lpf.enabled = v.Bus == "music" && lp > 0.5f;
                    lpf.cutoffFrequency = cutoff;
                    s.volume = bus * v.Fade * Envelope(v.P, s);
                }
            }

            if (Time.unscaledTime > listenerCheck)
            {
                listenerCheck = Time.unscaledTime + 1;
                if (FindObjectOfType<AudioListener>() == null) gameObject.AddComponent<AudioListener>();
            }
        }

        // Clip fade-in/out automation, in ms from the clip's own start and end.
        static float Envelope(Playlist p, AudioSource s)
        {
            if (!s.isPlaying || s.clip == null) return 1;
            double t = s.time * 1000.0 - p.Begin, len = p.SrcDur + p.End - p.Begin;
            float e = 1;
            if (p.FadeIn > 0) e = Mathf.Min(e, (float)(t / p.FadeIn));
            if (p.FadeOut > 0) e = Mathf.Min(e, (float)((len - t) / p.FadeOut));
            return Mathf.Clamp01(e);
        }

        // Keeps a playlist going: ends each iteration's clip at its trimmed end and schedules the
        // next iteration so that its entry cue lands exactly on the previous exit cue.
        bool Advance(Voice v, double now)
        {
            var p = v.P;
            for (int k = 0; k < 2; k++)
                if (v.Src[k].isPlaying && v.Src[k].time * 1000.0 >= v.SrcEnd[k]) v.Src[k].Stop();
            bool more = p.Loops == 0 || v.Iteration < p.Loops;
            if (more && now >= v.NextOrigin - 1.0) Schedule(v);
            else if (!more && !v.Src[0].isPlaying && !v.Src[1].isPlaying && now > v.StartAt[v.Next ^ 1]) return false;
            return true;
        }

        void Schedule(Voice v)
        {
            var p = v.P;
            var s = v.Src[v.Next];
            double segStart = System.Math.Max(0, p.PlayAt + p.Begin);
            double srcStart = segStart - p.PlayAt;
            s.Stop();
            s.clip = v.Clip;
            s.GetComponent<LomGain>().Value = GainOf(p.File);
            s.timeSamples = Mathf.Clamp((int)(srcStart / 1000.0 * v.Clip.frequency), 0, v.Clip.samples - 1);
            v.StartAt[v.Next] = v.NextOrigin + segStart / 1000.0;
            v.Origin[v.Next] = v.NextOrigin;
            s.PlayScheduled(v.StartAt[v.Next]);
            Debug.Log(Tag + p.Bus + " " + p.File + " iteration " + (v.Iteration + 1) + " from " + (srcStart / 1000).ToString("0.00") + "s to " + ((p.SrcDur + p.End) / 1000).ToString("0.00") + "s, in " + (v.StartAt[v.Next] - AudioSettings.dspTime).ToString("0.00") + "s");
            v.SrcEnd[v.Next] = p.SrcDur + p.End;
            v.Next ^= 1;
            v.Iteration++;
            double period = p.Exit - p.Entry;
            if (period <= 0) period = p.SrcDur + p.End - p.Begin;
            v.NextOrigin += period / 1000.0;
        }

        void Post(string name)
        {
            if (!tableLoaded)
            {
                // Only music, ambience and state matter this early; sounds posted now are dropped.
                if (!name.StartsWith("Sound_")) pending.Add(name);
                return;
            }
            if (!events.TryGetValue(name, out var acts))
            {
                Debug.Log(Tag + "unknown event " + name);
                return;
            }
            foreach (var a in acts)
            {
                var f = a.Split(',');
                switch (f[0])
                {
                    case "play":
                        int delay = int.Parse(f[1]), fadeIn = int.Parse(f[2]);
                        if (delay > 0) StartCoroutine(Delayed(delay, f[3], fadeIn));
                        else Play(f[3], fadeIn);
                        break;
                    case "stop":
                        StopBus(f[1], int.Parse(f[2]));
                        break;
                    case "stopall":
                        foreach (var bus in new[] { "music", "env", "sfx" }) StopBus(bus, int.Parse(f[1]));
                        break;
                    case "pause":
                        foreach (var v in voices) PauseVoice(v, true);
                        break;
                    case "resume":
                        foreach (var v in voices) PauseVoice(v, false);
                        break;
                    case "state":
                        SetState(uint.Parse(f[1]), uint.Parse(f[2]));
                        break;
                }
            }
        }

        IEnumerator Delayed(int ms, string target, int fadeIn)
        {
            yield return new WaitForSecondsRealtime(ms / 1000f);
            Play(target, fadeIn);
        }

        void Play(string target, int fadeIn)
        {
            var id = target.Substring(2);
            switch (target[0])
            {
                case 'S':
                    if (oneShots.TryGetValue(id, out var o) && o.Files.Length > 0)
                    {
                        // Random with "avoid repeating the last one", as the containers are set up.
                        int pick = Random.Range(0, o.Files.Length);
                        if (o.Files.Length > 1 && pick == o.Last) pick = (pick + 1 + Random.Range(0, o.Files.Length - 1)) % o.Files.Length;
                        o.Last = pick;
                        StartCoroutine(PlayOnce(o.Bus, o.Files[pick]));
                    }
                    break;
                case 'P':
                    if (playlists.TryGetValue(id, out var p)) StartCoroutine(PlayLoop(p, null, fadeIn));
                    break;
                case 'W':
                    if (switches.TryGetValue(id, out var w) && playlists.TryGetValue(Pick(w), out var wp)) StartCoroutine(PlayLoop(wp, id, fadeIn));
                    break;
            }
        }

        string Pick(Switch w) => states.TryGetValue(w.Group, out var st) && w.Map.TryGetValue(st, out var child) ? child : w.Default;

        void SetState(uint group, uint state)
        {
            if (states.TryGetValue(group, out var old) && old == state) return;
            states[group] = state;
            // A music switch that is playing follows the state at once.
            foreach (var v in voices.ToArray())
            {
                if (v.SwitchId == null || v.FadeTarget == 0 || !switches.TryGetValue(v.SwitchId, out var w) || w.Group != group) continue;
                if (!playlists.TryGetValue(Pick(w), out var p) || p == v.P) continue;
                FadeOut(v, 500);
                StartCoroutine(PlayLoop(p, v.SwitchId, 0));
            }
        }

        void StopBus(string bus, int fadeMs)
        {
            busSerial[bus]++;
            foreach (var v in voices)
                if (v.Bus == bus) FadeOut(v, fadeMs);
        }

        // Wwise ramps even a "no fade" stop over a few milliseconds; so does this, to avoid a click.
        static void FadeOut(Voice v, int ms)
        {
            v.FadeTarget = 0;
            v.FadeSpeed = 1f / Mathf.Max(ms, 20) * 1000f;
        }

        static void PauseVoice(Voice v, bool pause)
        {
            if (v.Paused == pause) return;
            v.Paused = pause;
            double now = AudioSettings.dspTime;
            if (pause)
            {
                v.PausedAt = now;
                for (int k = 0; k < v.Src.Length; k++)
                {
                    var s = v.Src[k];
                    if (v.P == null || now >= v.StartAt[k]) { s.Pause(); continue; }
                    // An iteration scheduled but not started yet is dropped and scheduled again after the pause.
                    s.Stop();
                    v.Iteration--;
                    v.Next = k;
                    v.NextOrigin = v.Origin[k];
                }
            }
            else
            {
                foreach (var s in v.Src) s.UnPause();
                v.NextOrigin += now - v.PausedAt;
            }
        }

        string Url(string id) => root + "/" + id + ".ogg";

        float GainOf(string id) => gains.TryGetValue(id, out var g) ? g : 1;

        IEnumerator PlayLoop(Playlist p, string switchId, int fadeIn)
        {
            int serial = busSerial[p.Bus];
            AudioClip clip = null;
            foreach (var kv in loopClips) if (kv.Key == p.File) { clip = kv.Value; break; }
            if (clip == null)
            {
                // Kept compressed in memory rather than streamed: the two sources of a voice play the
                // same clip at once, and seeking has to be exact.
                using (var req = UnityWebRequestMultimedia.GetAudioClip(Url(p.File), AudioType.OGGVORBIS))
                {
                    var dh = (DownloadHandlerAudioClip)req.downloadHandler;
                    dh.streamAudio = false;
                    dh.compressed = true;
                    yield return req.SendWebRequest();
                    if (req.result != UnityWebRequest.Result.Success)
                    {
                        Debug.Log(Tag + "load failed " + p.File + ": " + req.error);
                        yield break;
                    }
                    clip = DownloadHandlerAudioClip.GetContent(req);
                }
                loopClips.AddFirst(new KeyValuePair<string, AudioClip>(p.File, clip));
                while (loopClips.Count > 4)
                {
                    var old = loopClips.Last.Value;
                    loopClips.RemoveLast();
                    bool used = false;
                    foreach (var v in voices) if (v.Clip == old.Value) used = true;
                    if (!used) Destroy(old.Value);
                }
            }
            if (serial != busSerial[p.Bus]) yield break;
            var voice = new Voice
            {
                Bus = p.Bus, P = p, SwitchId = switchId, Clip = clip,
                Src = new[] { Source(p.Bus), Source(p.Bus) },
                // dspTime moves in audio-buffer steps; starting a little ahead keeps the first
                // iteration exactly on its origin, which every later loop point is counted from.
                NextOrigin = AudioSettings.dspTime + 0.15,
            };
            if (fadeIn > 0) { voice.Fade = 0; voice.FadeSpeed = 1000f / fadeIn; }
            voices.Add(voice);
            Schedule(voice);
        }

        IEnumerator PlayOnce(string bus, string id)
        {
            int serial = busSerial[bus];
            if (!sfx.ContainsKey(id)) yield return LoadSfx(id);
            if (!sfx.TryGetValue(id, out var clip) || serial != busSerial[bus]) yield break;
            var s = Source(bus);
            s.clip = clip;
            s.GetComponent<LomGain>().Value = GainOf(id);
            s.volume = BusVolume(bus);
            s.Play();
            voices.Add(new Voice { Bus = bus, Src = new[] { s } });
        }
    }

    // AudioSource.volume stops at 1, and Wwise boosts quiet files by up to +16 dB, so the gain is
    // applied to the samples instead. Clipped like the PC's final mix would be.
    public class LomGain : MonoBehaviour
    {
        public volatile float Value = 1;

        void OnAudioFilterRead(float[] data, int channels)
        {
            float g = Value;
            if (g == 1) return;
            for (int i = 0; i < data.Length; i++)
            {
                float v = data[i] * g;
                data[i] = v > 1 ? 1 : v < -1 ? -1 : v;
            }
        }
    }
}
