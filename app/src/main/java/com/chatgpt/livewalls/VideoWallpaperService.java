package com.chatgpt.livewalls;

import android.media.MediaPlayer;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

public class VideoWallpaperService extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new VideoEngine();
    }

    private class VideoEngine extends Engine {
        private MediaPlayer player;
        private boolean visible;

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            startPlayer(holder);
        }

        private void startPlayer(SurfaceHolder holder) {
            releasePlayer();
            int selected = getSharedPreferences("wallpaper", MODE_PRIVATE)
                    .getInt("selected_video", 0);
            int resId = selected == 1 ? R.raw.wallpaper2 : (selected == 2 ? R.raw.wallpaper3 : R.raw.wallpaper1);
            try {
                player = MediaPlayer.create(VideoWallpaperService.this, resId);
                if (player == null) return;
                player.setSurface(holder.getSurface());
                player.setLooping(true);
                player.setVolume(0f, 0f);
                try { player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING); } catch (Exception ignored) {}
                if (visible) player.start();
            } catch (Exception e) {
                releasePlayer();
            }
        }

        @Override
        public void onVisibilityChanged(boolean visibleNow) {
            visible = visibleNow;
            if (player == null) return;
            try {
                if (visibleNow) player.start(); else player.pause();
            } catch (Exception ignored) {}
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            releasePlayer();
        }

        @Override
        public void onDestroy() {
            releasePlayer();
            super.onDestroy();
        }

        private void releasePlayer() {
            if (player != null) {
                try { player.stop(); } catch (Exception ignored) {}
                try { player.release(); } catch (Exception ignored) {}
                player = null;
            }
        }
    }
}
