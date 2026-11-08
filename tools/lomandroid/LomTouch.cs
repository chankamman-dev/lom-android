using System;
using System.Reflection;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.LowLevel;

namespace LomAndroid
{
    // The game side of the on-screen controls (dev.kmlom.cloud.TouchPad, Android views over the game).
    // Each frame it reads their state over JNI and mirrors it into a virtual Input System gamepad, so
    // every action the game binds to <Gamepad> works from the screen. One-shot commands open 咖啡伴侣's
    // windows directly (their PC hotkeys need a keyboard).
    public class LomTouch : MonoBehaviour
    {
        static GameObject host;
        AndroidJavaClass bridge;
        Gamepad pad;
        uint lastButtons;
        Vector2 lastStick;
        float lastLt, lastRt;

        public static void Ensure()
        {
            if (host != null) return;
            host = new GameObject("LomTouch");
            DontDestroyOnLoad(host);
            host.AddComponent<LomTouch>();
        }

        void Start()
        {
            try
            {
                bridge = new AndroidJavaClass("dev.kmlom.cloud.CloudBridge");
                using (var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                using (var activity = player.GetStatic<AndroidJavaObject>("currentActivity"))
                    bridge.CallStatic("attachTouch", activity);
            }
            catch (Exception e)
            {
                Debug.Log("[LomTouch] " + e.Message);
                enabled = false;
            }
        }

        void Update()
        {
            float[] s;
            try { s = bridge.CallStatic<float[]>("touchState"); }
            catch (Exception e) { Debug.Log("[LomTouch] " + e.Message); enabled = false; return; }
            if (s == null || s.Length < 4) return;

            int bits = (int)s[2], cmd = (int)s[3];
            var stick = new Vector2(s[0], s[1]);
            uint buttons = (uint)(bits & 0x3FFF);
            float lt = (bits & (1 << 14)) != 0 ? 1f : 0f, rt = (bits & (1 << 15)) != 0 ? 1f : 0f;
            if (buttons != lastButtons || stick != lastStick || lt != lastLt || rt != lastRt)
            {
                // The device appears on first use, so a player who never touches the controls keeps
                // the game's mouse/touch prompts.
                if (pad == null) pad = InputSystem.AddDevice<Gamepad>("LomTouchGamepad");
                var st = new GamepadState { buttons = buttons, leftStick = stick, leftTrigger = lt, rightTrigger = rt };
                InputSystem.QueueStateEvent(pad, st);
                lastButtons = buttons;
                lastStick = stick;
                lastLt = lt;
                lastRt = rt;
            }
            if ((cmd & 1) != 0) Companion("ToggleSettings");
            if ((cmd & 2) != 0) Companion("ToggleAi");
        }

        // LOMAssistant.Features.Story.StoryReviewPanel.ToggleSettings / ToggleAi (what its panel
        // hotkeys call).
        static void Companion(string method)
        {
            try
            {
                var t = Type.GetType("LOMAssistant.Features.Story.StoryReviewPanel, LOMAssistant", false);
                var m = t?.GetMethod(method, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static);
                if (m == null) { Debug.Log("[LomTouch] 咖啡伴侣没有加载"); return; }
                m.Invoke(null, null);
            }
            catch (Exception e)
            {
                Debug.Log("[LomTouch] " + method + ": " + e.Message);
            }
        }

        void OnDestroy()
        {
            if (pad != null) InputSystem.RemoveDevice(pad);
        }
    }
}
