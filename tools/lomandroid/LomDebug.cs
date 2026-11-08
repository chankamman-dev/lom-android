using System.IO;
using Mortal.Core;
using UnityEngine;

namespace LomAndroid
{
    // Test hooks for features that are hard to reach by playing. Does nothing unless the file
    // <persistentDataPath>/lomdebug.txt exists; each line is one command, and the file is deleted
    // once read.
    //   video=<key>   plays a CG exactly as the story does (CgVideoPanel.Open)
    //   event=<name>  posts a sound event
    //   audio         logs what LomAudio is playing
    public class LomDebug : MonoBehaviour
    {
        const string Tag = "[LomDebug] ";
        float next;

        void Update()
        {
            if (Time.unscaledTime < next) return;
            next = Time.unscaledTime + 2;
            var path = Path.Combine(Application.persistentDataPath, "lomdebug.txt");
            if (!File.Exists(path)) return;
            string[] lines;
            try
            {
                lines = File.ReadAllLines(path);
                File.Delete(path);
            }
            catch (System.Exception e)
            {
                Debug.Log(Tag + "cannot read commands: " + e.Message);
                return;
            }
            foreach (var line in lines)
            {
                var kv = line.Trim().Split(new[] { '=' }, 2);
                if (kv.Length == 2 && kv[0] == "video") StartCoroutine(PlayVideo(kv[1]));
                else if (kv.Length == 2 && kv[0] == "event") LomAudio.PostEvent(kv[1]);
                else if (kv[0] == "audio") Debug.Log(Tag + "audio:\n" + LomAudio.Describe());
            }
        }

        System.Collections.IEnumerator PlayVideo(string key)
        {
            var panel = FindObjectOfType<CgVideoPanel>(true);
            if (panel == null)
            {
                Debug.Log(Tag + "no CgVideoPanel in the scene");
                yield break;
            }
            Debug.Log(Tag + "video " + key + " start");
            float t = Time.realtimeSinceStartup;
            yield return panel.StartCoroutine(panel.Open(key, 0));
            Debug.Log(Tag + "video " + key + " finished after " + (Time.realtimeSinceStartup - t).ToString("0.0") + "s");
        }
    }
}
