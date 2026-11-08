using System;
using System.Linq;
using Mortal.Core;
using UnityEngine;

namespace LomAndroid
{
    // Woven into SaveSystem and LibrarySystem; hands save writes, achievements and the game's start
    // to the Java side (dev.kmlom.cloud.CloudBridge).
    public static class LomCloud
    {
        static bool started;

        public static void Saved() => Call("onSaved");

        // SaveSystem.SetAchivement, which on PC calls SteamUserStats.SetAchievement("ACH_" + id).
        public static void Achievement(string id) => Call("onAchievements", "ACH_" + id);

        // The end of LibrarySystem.Load: the universe save is in. Starts Steam playtime and offers
        // every achievement already earned, so ones from before signing in reach Steam too.
        public static void LibraryLoaded(LibrarySystem library)
        {
            if (!started)
            {
                started = true;
                Call("onGameStarted");
            }
            try
            {
                var earned = library.Achievement.List.Where(i => i != null && i.Count > 0).Select(i => "ACH_" + i.name).ToArray();
                Debug.Log("[LomCloud] achievements held: " + earned.Length);
                if (earned.Length > 0) Call("onAchievements", string.Join(",", earned));
            }
            catch (Exception e)
            {
                Debug.Log("[LomCloud] " + e.Message);
            }
        }

        static void Call(string method, string arg = null)
        {
            try
            {
                using (var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                using (var activity = player.GetStatic<AndroidJavaObject>("currentActivity"))
                using (var bridge = new AndroidJavaClass("dev.kmlom.cloud.CloudBridge"))
                {
                    if (arg == null) bridge.CallStatic(method, activity);
                    else bridge.CallStatic(method, activity, arg);
                }
            }
            catch (Exception e)
            {
                Debug.Log("[LomCloud] " + e.Message);
            }
        }
    }
}
