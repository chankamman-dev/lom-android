using System;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.Rendering.Universal;

namespace LomAndroid
{
    // Player settings from the launcher (dev.kmlom.cloud.Settings, read through CloudBridge.intSetting).
    // Woven at the end of SystemSettings.SetResolution, which the game runs at start and whenever the
    // resolution changes.
    //
    // The game never sets Application.targetFrameRate. On PC that means "as fast as vsync allows"; on
    // Android Unity's default is 30, so without this the port runs at 30 fps.
    public static class LomPerf
    {
        static bool logged;
        static GameObject meter;

        public static void Apply()
        {
            try
            {
                int fps = Setting("fps", 60), scale = Setting("scale", 100);
                QualitySettings.vSyncCount = 0;
                Application.targetFrameRate = fps;
                float rs = Mathf.Clamp(scale, 50, 100) / 100f;
                if (GraphicsSettings.renderPipelineAsset is UniversalRenderPipelineAsset g) g.renderScale = rs;
                if (QualitySettings.renderPipeline is UniversalRenderPipelineAsset q) q.renderScale = rs;
                Application.backgroundLoadingPriority = ThreadPriority.High;
                if (!logged)
                {
                    logged = true;
                    LomTouch.Ensure();
                    Debug.Log("[LomPerf] target " + fps + " fps, render scale " + scale + "%");
                }
                if (Setting("fpslog", 0) != 0 && meter == null)
                {
                    meter = new GameObject("LomFpsLog");
                    UnityEngine.Object.DontDestroyOnLoad(meter);
                    meter.AddComponent<LomFpsLog>();
                }
            }
            catch (Exception e)
            {
                Debug.Log("[LomPerf] " + e.Message);
            }
        }

        static int Setting(string key, int def)
        {
            using (var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = player.GetStatic<AndroidJavaObject>("currentActivity"))
            using (var bridge = new AndroidJavaClass("dev.kmlom.cloud.CloudBridge"))
                return bridge.CallStatic<int>("intSetting", activity, key, def);
        }
    }

    // Developer frame-rate log (launcher setting "帧率日志"): every 5 s, average fps and the slowest
    // frame, to logcat (tag Unity, prefix [LomFps]).
    public class LomFpsLog : MonoBehaviour
    {
        float since, worst;
        int frames;

        void Update()
        {
            float dt = Time.unscaledDeltaTime;
            frames++;
            since += dt;
            if (dt > worst) worst = dt;
            if (since >= 5f)
            {
                Debug.Log("[LomFps] fps " + (frames / since).ToString("F1") + ", slowest " + (worst * 1000f).ToString("F0") + " ms, target " + Application.targetFrameRate
                          + ", " + Screen.width + "x" + Screen.height);
                since = 0;
                frames = 0;
                worst = 0;
            }
        }
    }
}
