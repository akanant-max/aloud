// Puts Aloud into Windows' System Media Transport Controls.
//
// Aloud itself runs on the JVM, which has no access to them. This helper owns the controls and
// talks to Aloud over its standard input and output, one line per message:
//
//   from Aloud:  meta<TAB>title<TAB>artist<TAB>album<TAB>cover path
//                state<TAB>playing | paused | stopped
//                quit
//   to Aloud:    ready, play, pause, toggle, stop, next, previous, error<TAB>message
//
// It exits when Aloud closes its input, so it can never outlive the app.

using System;
using System.IO;
using Windows.Media;
using Windows.Media.Playback;
using Windows.Storage;
using Windows.Storage.Streams;

namespace AloudControls
{
    internal static class Program
    {
        private static readonly object OutputLock = new object();
        private static SystemMediaTransportControls controls;

        [MTAThread]
        private static int Main()
        {
            // A media player that never plays anything. It is only here because it is the way a
            // desktop app without a window of its own gets hold of the media controls.
            var player = new MediaPlayer();
            player.CommandManager.IsEnabled = false;

            controls = player.SystemMediaTransportControls;
            controls.IsEnabled = true;
            controls.IsPlayEnabled = true;
            controls.IsPauseEnabled = true;
            controls.IsStopEnabled = true;
            controls.IsNextEnabled = true;
            controls.IsPreviousEnabled = true;
            controls.PlaybackStatus = MediaPlaybackStatus.Closed;
            controls.ButtonPressed += OnButtonPressed;

            Send("ready");

            string line;
            while ((line = Console.In.ReadLine()) != null)
            {
                if (line == "quit") break;
                try
                {
                    Handle(line);
                }
                catch (Exception e)
                {
                    Send("error\t" + Clean(e.Message));
                }
            }

            controls.PlaybackStatus = MediaPlaybackStatus.Closed;
            controls.DisplayUpdater.ClearAll();
            controls.DisplayUpdater.Update();
            player.Dispose();
            return 0;
        }

        private static void OnButtonPressed(
            SystemMediaTransportControls sender,
            SystemMediaTransportControlsButtonPressedEventArgs args)
        {
            switch (args.Button)
            {
                case SystemMediaTransportControlsButton.Play: Send("play"); break;
                case SystemMediaTransportControlsButton.Pause: Send("pause"); break;
                case SystemMediaTransportControlsButton.Stop: Send("stop"); break;
                case SystemMediaTransportControlsButton.Next: Send("next"); break;
                case SystemMediaTransportControlsButton.Previous: Send("previous"); break;
                case SystemMediaTransportControlsButton.FastForward: Send("forward"); break;
                case SystemMediaTransportControlsButton.Rewind: Send("rewind"); break;
            }
        }

        private static void Handle(string line)
        {
            var parts = line.Split('\t');
            switch (parts[0])
            {
                case "state":
                    var state = Part(parts, 1);
                    controls.PlaybackStatus =
                        state == "playing" ? MediaPlaybackStatus.Playing :
                        state == "paused" ? MediaPlaybackStatus.Paused :
                        MediaPlaybackStatus.Stopped;
                    break;

                case "meta":
                    var updater = controls.DisplayUpdater;
                    updater.ClearAll();
                    updater.Type = MediaPlaybackType.Music;
                    updater.MusicProperties.Title = Part(parts, 1);
                    updater.MusicProperties.Artist = Part(parts, 2);
                    updater.MusicProperties.AlbumTitle = Part(parts, 3);
                    var cover = Part(parts, 4);
                    if (cover.Length > 0 && File.Exists(cover))
                    {
                        var file = StorageFile.GetFileFromPathAsync(cover).AsTask().GetAwaiter().GetResult();
                        updater.Thumbnail = RandomAccessStreamReference.CreateFromFile(file);
                    }
                    updater.Update();
                    break;
            }
        }

        private static string Part(string[] parts, int index) =>
            index < parts.Length ? parts[index] : "";

        private static string Clean(string text) =>
            (text ?? "").Replace('\t', ' ').Replace('\r', ' ').Replace('\n', ' ');

        private static void Send(string message)
        {
            lock (OutputLock)
            {
                Console.Out.WriteLine(message);
                Console.Out.Flush();
            }
        }
    }
}
